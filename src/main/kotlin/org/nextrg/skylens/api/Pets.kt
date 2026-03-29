package org.nextrg.skylens.api

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.nextrg.skylens.features.PetOverlay.hideOverlay
import org.nextrg.skylens.features.PetOverlay.levelUp
import org.nextrg.skylens.features.PetOverlay.showOverlay
import org.nextrg.skylens.features.PetOverlay.updatePet
import org.nextrg.skylens.features.PetOverlay.updateStats
import org.nextrg.skylens.helpers.ItemsUtil.tooltipFromItemStack
import org.nextrg.skylens.helpers.OtherUtil.errorMessage
import org.nextrg.skylens.helpers.OtherUtil.getTabData
import org.nextrg.skylens.helpers.OtherUtil.getTextureFromNeu
import org.nextrg.skylens.helpers.StringsUtil.colorCodeToName
import org.nextrg.skylens.helpers.StringsUtil.colorToRarity
import org.nextrg.skylens.helpers.VariablesUtil.sToMs
import org.nextrg.skylens.helpers.VariablesUtil.toFixed
import org.nextrg.skylens.helpers.config.DataHolders
import tech.thatgravyboat.skyblockapi.api.profile.profile.ProfileAPI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object Pets {
    private val AUTOPET_PATTERN: Pattern = Pattern.compile("^Autopet equipped your \\[Lvl (\\d+)] (.+)! VIEW RULE$")
    private val SUMMON_PATTERN: Pattern = Pattern.compile("You (summoned|despawned) your (.+?)!")
    private val LEVELUP_PATTERN: Pattern = Pattern.compile("Your (.+?) leveled up to level (\\d+)!")
    private val PETSPAGE_PATTERN: Pattern = Pattern.compile("Pets \\((\\d+)\\/\\d+\\)")

    private val scheduler = Executors.newScheduledThreadPool(1)
    private var scheduledResetTask: ScheduledFuture<*>? = null

    private var cachedPetsPage1: MutableList<ItemStack> = mutableListOf()
    private var cachedPetsPage2: MutableList<ItemStack> = mutableListOf()
    private var cachedPetsPage3: MutableList<ItemStack> = mutableListOf()
    private var isPetMenu = false
    private var currentPetScreen: ContainerScreen? = null
    private var hasCached = false

    private var noCacheMode = true
    private var noCacheLevel = 1
    private var noCacheMaxLevel = 100
    private var noCacheRarity = "common"

    private var lastUpdate = System.currentTimeMillis()
    private var updateByTab = true

    private var currentPet: ItemStack = ItemStack(Items.BONE)
    private var level: Int = 1
    private var maxLevel: Int = 100
    private var xp: Float = 0f
    private var heldItem: String = ""

    private var loadedProfileId: String? = null
    private var restoredForProfile = false

    fun init() {
        ClientReceiveMessageEvents.ALLOW_GAME.register(ClientReceiveMessageEvents.AllowGame { text, overlay ->
            messageEvents(text)
            return@AllowGame true
        })
        ScreenEvents.BEFORE_INIT.register(ScreenEvents.BeforeInit { _, screen, _, _ ->
            if (screen is ContainerScreen && screen.title.string.startsWith("Pets")) {
                readInventory(screen)
            }
        })
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            restoreFromStorageIfNeeded()
            checkPetScreen(client)
            readTab(client, true)
        })
        ClientLifecycleEvents.CLIENT_STOPPING.register(ClientLifecycleEvents.ClientStopping { client ->
            saveToFile()
        })
    }

    data class PetData(
        var name: String = "",
        var level: Int = 1,
        var maxLevel: Int = 100,
        var xp: Float = 0f,
        var heldItem: String = "",
        var rarity: String = "common",
    )

    val storage = DataHolders.createProfileSpecific<PetData>("pets", ::PetData)

    fun saveToFile() {
        storage.mutateAndSave { data ->
            data.name = normalizePetNameForStorage(currentPet.customName?.string.orEmpty())
            data.level = level
            data.maxLevel = maxLevel
            data.xp = xp
            data.rarity = getPetRarity(getPetRarityText(currentPet.customName))
            data.heldItem = heldItem
        }
    }

    private fun readFromFile(): Boolean {
        val data = storage.data
        if (data.name.isBlank()) return false

        val normalizedPetName = normalizePetNameForStorage(data.name)
        currentPet = getTextureFromNeu(normalizedPetName, true)

        noCacheMode = true
        level = data.level
        maxLevel = data.maxLevel
        xp = data.xp
        heldItem = data.heldItem

        noCacheLevel = data.level
        noCacheMaxLevel = data.maxLevel
        noCacheRarity = data.rarity.ifBlank { "common" }
        return true
    }

    private fun normalizePetNameForStorage(rawName: String): String {
        val stripped = ChatFormatting.stripFormatting(rawName).orEmpty()
        val withoutStars = stripped
            .replace(" ✦", "")
            .replace("⭐ ", "")
            .replace("⭐", "")
            .trim()

        return removeLevel(withoutStars)
    }

    private fun restoreFromStorageIfNeeded() {
        val profileId = ProfileAPI.profileId?.toString() ?: return

        if (profileId!=loadedProfileId) {
            loadedProfileId = profileId
            restoredForProfile = false
        }

        if (restoredForProfile) return
        restoredForProfile = true

        if (!readFromFile()) return

        updatePet()
        updateStats()
        showOverlay()
    }


    fun getCurrentPet(): ItemStack = currentPet

    fun getPetLevel(): Int = if (noCacheMode) noCacheLevel else level
    fun getPetMaxLevel(): Int = if (noCacheMode) noCacheMaxLevel else maxLevel
    fun getPetXp(): Float = if (noCacheMode && noCacheLevel==noCacheMaxLevel) 1f else xp
    fun getPetHeldItem(): String = heldItem

    fun getPetRarity(element: Component): String {
        if (noCacheMode) {
            return noCacheRarity
        }
        return colorToRarity(element.style.color.toString())
    }

    fun getPetRarityText(customName: Component?): Component {
        val siblings = customName?.siblings
        return when {
            siblings==null || siblings.size <= 1 -> Component.empty()
            siblings[1].string.contains("[") -> siblings.getOrNull(2) ?: Component.empty()
            else -> siblings[1]
        }
    }

    fun isGoldenDragon(string: String): Boolean {
        return string.contains(Regex("(Golden|Jade) Dragon"))
    }

    private fun checkPetScreen(client: Minecraft) {
        val screen = client.screen
        if (isPetMenu && (screen !is ContainerScreen || !screen.title.string.startsWith("Pets"))) {
            isPetMenu = false
            currentPetScreen = null
        }
    }

    private fun getPetSlots(screen: ContainerScreen): List<Slot> {
        return screen.menu.slots
            .filter { it.index in 10..43 && it.index % 9 in 1..7 }
    }

    private fun getPetStats(pet: ItemStack) {
        val tooltip = tooltipFromItemStack(pet)
        val petName = pet.customName ?: return
        level = parseLevel(petName, 0)
        maxLevel = if (isGoldenDragon(petName.string)) 200 else 100
        heldItem = ""

        for (line in tooltip) {
            val string = line.toString()

            if (string.contains("Progress to") && string.contains("%")) {
                if (line.siblings.size > 1) {
                    val displayXp = line.siblings[1].string.replace("%", "")
                    xp = (displayXp.toFloat() / 100f).toFixed(3)
                }
            }

            if (string.contains("MAX LEVEL")) {
                xp = 1f
                level = maxLevel
            }

            if (string.contains("Held Item:")) {
                if (line.siblings.size > 1) {
                    heldItem = line.siblings[1].string
                }
            }
        }

        updateStats()
    }

    private fun findTabIndices(list: List<Component>): Pair<Int, Int> {
        return list.withIndex().fold(3 to 45) { (lvlIdx, xpIdx), (i, text) ->
            val s = text.toString()
            val newLvl = if ("[Lvl" in s) i else lvlIdx
            val newXp = if ("XP" in s && "/" in s && "%" in s) i else xpIdx
            newLvl to newXp
        }
    }

    private fun isFavorite(text: Component?) = text?.string?.contains("⭐")==true
    private fun favoriteMargin(text: Component?) = if (isFavorite(text)) 1 else 0

    private fun parseLevel(text: Component?, index: Int): Int {
        return text?.siblings?.getOrNull(index + favoriteMargin(text))?.string
            ?.replace(Regex("""\[Lvl (\d+)]"""), "$1")
            ?.trim()
            ?.toIntOrNull() ?: 1
    }

    private fun parseXp(text: Component?): Float {
        return text?.siblings?.getOrNull(4 + favoriteMargin(text))?.string
            ?.removePrefix("(")
            ?.removeSuffix("%)")
            ?.trim()
            ?.toFloatOrNull()
            ?.div(100) ?: 0f
    }

    private fun readTab(client: Minecraft, cooldown: Boolean) {
        if (System.currentTimeMillis() - lastUpdate < 2500 && cooldown) return
        lastUpdate = System.currentTimeMillis()

        if (!updateByTab) return

        val currentPetText = getPetRarityText(currentPet.customName)
        if (currentPetText==Component.empty() && !noCacheMode) return

        val currentRarity = getPetRarity(currentPetText)

        val list = getTabData(client)
        val (levelIndex, xpIndex) = findTabIndices(list)

        val tabPet = list.getOrNull(levelIndex) ?: return
        if (tabPet.siblings.size < 3) return

        val tabName = tabPet.siblings[2]
        val tabRarity = getPetRarity(tabName)
        val tabPetName = tabName.string

        if (currentPetText.string!=tabPetName && currentRarity!=tabRarity && !noCacheMode) return

        level = parseLevel(tabPet, 1)
        maxLevel = if (isGoldenDragon(tabPetName)) 200 else 100
        xp = if (!tooltipFromItemStack(currentPet).toString().contains("MAX LEVEL")) {
            parseXp(list.getOrNull(xpIndex))
        } else {
            1f
        }

        updateStats()
    }

    private fun readInventory(screen: ContainerScreen) {
        if (screen!=currentPetScreen) {
            currentPetScreen = screen
            hasCached = false
        }

        isPetMenu = true
        noCacheMode = false

        val screenTitle = screen.title.string
        val matcher = PETSPAGE_PATTERN.matcher(screenTitle)
        var page = 1
        if (matcher.find()) {
            page = matcher.group(1).toIntOrNull() ?: 1
        }

        val currentCachedPetsPage = when (page) {
            2 -> cachedPetsPage2
            3 -> cachedPetsPage3
            else -> cachedPetsPage1
        }

        var ticks = 0
        ScreenEvents.afterTick(screen).register(ScreenEvents.AfterTick { _ ->
            if (!isPetMenu || hasCached) return@AfterTick

            if (ticks < 2) {
                ticks++
                return@AfterTick
            }

            currentCachedPetsPage.clear()

            var equippedPet = ""
            var rarity = "common"

            getPetSlots(screen).forEach { slot ->
                val stack = slot.item
                if (!stack.isEmpty && stack.item==Items.PLAYER_HEAD) {
                    currentCachedPetsPage.add(stack)
                    val content = tooltipFromItemStack(stack).toString()
                    if ("Click to despawn!" in content && stack.customName!=null) {
                        equippedPet = stack.customName!!.string
                        rarity = getPetRarity(getPetRarityText(stack.customName!!))
                    }
                }
            }

            findPetFromInventory(equippedPet, rarity)
            hasCached = true
        })
    }

    private fun preventTabUpdate() {
        updateByTab = false
        scheduledResetTask?.cancel(false)

        scheduledResetTask = scheduler.schedule({
            updateByTab = true; scheduledResetTask = null
        }, sToMs(2.5f), TimeUnit.MILLISECONDS)
    }

    private fun removeLevel(petName: String): String {
        return petName.substringAfter("] ", petName).trim()
    }

    private fun findPetFromInventory(petName: String, rarity: String) {
        val noSymbol = petName.replace(" ✦", "").replace("⭐ ", "")
        val petNameWithoutLvl = removeLevel(noSymbol)

        if (noCacheMode) {
            setPet(getTextureFromNeu(noSymbol, true))
            return
        }

        val allPets = cachedPetsPage1 + cachedPetsPage2 + cachedPetsPage3

        for (pet in allPets) {
            val cachedPetName = pet.customName
            val nameWithoutFormat = ChatFormatting.stripFormatting(cachedPetName?.string)
            val petRarity = getPetRarity(getPetRarityText(pet.customName))

            if (nameWithoutFormat?.contains(petNameWithoutLvl)==true && petRarity==rarity) {
                setPet(pet)
                break
            }
        }
    }

    private fun setPet(pet: ItemStack) {
        preventTabUpdate()
        currentPet = pet
        updatePet()
        getPetStats(pet)
    }

    private fun messageEvents(message: Component) {
        val string = message.string
        val content = ChatFormatting.stripFormatting(string).toString()
        if (content.contains("You summoned your") || content.contains("You despawned your")) {
            val matcher = SUMMON_PATTERN.matcher(content)
            if (matcher.find()) {
                if (matcher.group(1)=="summoned") {
                    var rarity = "common"
                    try {
                        if (message.siblings.size > 1) {
                            rarity = colorToRarity(message.siblings[1].style.color.toString())
                        }
                    } catch (e: Exception) {
                        errorMessage("Failed to retrieve summoned pet rarity", e)
                    }
                    findPetFromInventory(matcher.group(2), rarity)
                    showOverlay()
                }
                if (matcher.group(1)=="despawned") {
                    hideOverlay()
                }
            }
        }

        if (content.contains("Autopet")) {
            val matcher = AUTOPET_PATTERN.matcher(content)
            if (matcher.find()) {
                val autopetLevel = matcher.group(1)
                val autopetPet = matcher.group(2)
                val matchIndex = string.indexOf(autopetLevel) + autopetLevel.length - 2
                var rarity = "common"

                if (matchIndex!=-1 && matchIndex + 6 <= string.length) {
                    val input = string.substring(matchIndex + 4, matchIndex + 6)
                    val color = colorCodeToName(input)
                    if (color.size > 1) {
                        rarity = color[1]
                    }
                }

                noCacheLevel = autopetLevel.toIntOrNull() ?: 1
                noCacheRarity = rarity
                noCacheMaxLevel = if (isGoldenDragon(autopetPet)) 200 else 100

                findPetFromInventory(autopetPet, rarity)
                showOverlay()
            }
        }

        if (content.contains("Welcome to Hypixel Skyblock!")) {
            scheduler.schedule({
                readTab(Minecraft.getInstance(), false)
            }, sToMs(1.25f), TimeUnit.MILLISECONDS)
        }

        if (content.contains("leveled up to level")) {
            val matcher = LEVELUP_PATTERN.matcher(content)
            if (matcher.find() && content.contains(getPetRarityText(currentPet.customName).string)) {
                level = matcher.group(2)?.toIntOrNull() ?: level
                xp = 0f
                updateStats()
                levelUp()
                preventTabUpdate()
            }
        }
    }
}