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
    private val tabCooldownLiveMs = 500L
    private val tabCooldownFallbackMs = 160L
    private var updateByTab = true

    private var currentPet: ItemStack = ItemStack(Items.BONE)
    private var level: Int = 1
    private var maxLevel: Int = 100
    private var xp: Float = 0f
    private var heldItem: String = ""
    private var currentPetName: String = ""
    private var currentRarity: String = "common"

    private var loadedProfileId: String? = null
    private var restoredForProfile = false
    private var pendingTabName: String? = null
    private var pendingTabLevel: Int? = null
    private var pendingTabStableTicks = 0
    private var fastSyncUntilMs = 0L

    private fun enableFastSync(durationMs: Long = 1500L) {
        fastSyncUntilMs = System.currentTimeMillis() + durationMs
    }

    private fun requiredTabSnapshots(nowMs: Long): Int {
        return if (nowMs <= fastSyncUntilMs) 1 else 2
    }

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
            val fallbackName = normalizePetName(currentPet.customName?.string.orEmpty())
            data.name = (if (currentPetName.isNotBlank()) currentPetName else fallbackName)
            data.level = level
            data.maxLevel = maxLevel
            data.xp = xp
            data.rarity = currentRarity
            data.heldItem = heldItem
        }
    }

    private fun readFromFile(): Boolean {
        val data = storage.data
        if (data.name.isBlank()) return false

        currentPetName = normalizePetName(data.name)
        currentRarity = data.rarity.ifBlank { "common" }
        currentPet = getTextureFromNeu(currentPetName, true)

        noCacheMode = true
        level = data.level
        maxLevel = data.maxLevel
        xp = data.xp
        heldItem = data.heldItem

        noCacheLevel = data.level
        noCacheMaxLevel = data.maxLevel
        noCacheRarity = currentRarity
        return true
    }

    private fun restoreFromStorageIfNeeded() {
        val profileId = ProfileAPI.profileId?.toString() ?: return

        if (profileId != loadedProfileId) {
            loadedProfileId = profileId
            restoredForProfile = false
            pendingTabName = null
            pendingTabLevel = null
            pendingTabStableTicks = 0
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

        val color = element.style.color?.toString()
        return if (color.isNullOrBlank()) currentRarity else colorToRarity(color)
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
        return string.contains(Regex("(Golden|Jade|Rose) Dragon"))
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
            val hasXpKeyword = s.contains("XP", ignoreCase = true)
            val hasPercent = s.contains("%")
            val hasSeparator = s.contains("/") || s.contains(":")
            val newXp = if (hasXpKeyword && hasPercent && hasSeparator) i else xpIdx
            newLvl to newXp
        }
    }

    private fun isFavorite(text: Component?) = text?.string?.contains("⭐") == true
    private fun favoriteMargin(text: Component?) = if (isFavorite(text)) 1 else 0

    private fun parseLevel(text: Component?, index: Int): Int {
        return text?.siblings?.getOrNull(index + favoriteMargin(text))?.string
            ?.replace(Regex("""\[Lvl (\d+)]"""), "$1")
            ?.trim()
            ?.toIntOrNull() ?: 1
    }

    private fun parseXpOrNull(text: Component?): Float? {
        if (text == null) return null

        val candidates = buildList {
            add(text.string)
            add(text.toString())
            text.siblings.forEach { add(it.string) }
        }

        for (candidate in candidates) {
            val match = Regex("""([0-9]+(?:[.,][0-9]+)?)%""").find(candidate)
            val raw = match?.groupValues?.getOrNull(1) ?: continue
            val normalized = raw.replace(',', '.')
            val value = normalized.toFloatOrNull() ?: continue
            return (value / 100f).coerceIn(0f, 1f)
        }

        return null
    }

    private fun extractXpFromTab(list: List<Component>, levelIndex: Int, xpIndex: Int): Float? {
        val preferred = listOfNotNull(
            list.getOrNull(xpIndex),
            list.getOrNull(levelIndex + 1),
            list.getOrNull(levelIndex + 2),
            list.getOrNull(levelIndex - 1)
        )

        preferred.firstNotNullOfOrNull { candidate ->
            if (candidate.toString().contains("XP", ignoreCase = true)) parseXpOrNull(candidate) else null
        }?.let { return it }

        preferred.firstNotNullOfOrNull(::parseXpOrNull)?.let { return it }

        list.firstNotNullOfOrNull { candidate ->
            if (candidate.toString().contains("XP", ignoreCase = true)) parseXpOrNull(candidate) else null
        }?.let { return it }

        return null
    }

    private fun readTab(client: Minecraft, cooldown: Boolean) {
        val now = System.currentTimeMillis()
        val cooldownMs = if (noCacheMode) tabCooldownFallbackMs else tabCooldownLiveMs
        if (cooldown && now - lastUpdate < cooldownMs) return
        lastUpdate = now

        if (!updateByTab) return

        val currentPetText = getPetRarityText(currentPet.customName)
        if (currentPetText == Component.empty() && !noCacheMode && currentPetName.isBlank()) return

        val currentKnownName = if (currentPetName.isNotBlank()) currentPetName else normalizePetName(currentPetText.string)

        val list = getTabData(client)
        val (levelIndex, xpIndex) = findTabIndices(list)

        val tabPet = list.getOrNull(levelIndex) ?: return
        if (tabPet.siblings.size < 3 || !tabPet.string.contains("[Lvl")) return

        val tabName = tabPet.siblings[2]
        val tabRarity = getPetRarity(tabName)
        val tabPetName = tabName.string
        val normalizedTabName = normalizePetName(tabPetName)
        if (normalizedTabName.isBlank()) return

        val parsedLevel = parseLevel(tabPet, 1)
        if (parsedLevel <= 0) return

        if (!noCacheMode) {
            if (currentKnownName.isNotBlank() && normalizedTabName != currentKnownName) return
            if (currentKnownName.isBlank() && tabRarity != currentRarity) return
        }

        if (noCacheMode) {
            val sameSnapshot = pendingTabName == normalizedTabName && pendingTabLevel == parsedLevel
            if (sameSnapshot) {
                pendingTabStableTicks++
            } else {
                pendingTabName = normalizedTabName
                pendingTabLevel = parsedLevel
                pendingTabStableTicks = 1
            }

            val requiredSnapshots = requiredTabSnapshots(now)
            if (pendingTabStableTicks < requiredSnapshots) return
        }

        val hadDifferentPet = currentPetName.isNotBlank() && currentPetName != normalizedTabName
        currentPetName = normalizedTabName
        currentRarity = tabRarity

        level = parsedLevel
        maxLevel = if (isGoldenDragon(tabPetName)) 200 else 100

        val xpFromTab = parseXpOrNull(list.getOrNull(xpIndex))
            ?: extractXpFromTab(list, levelIndex, xpIndex)

        xp = if (level >= maxLevel) {
            1f
        } else {
            xpFromTab ?: xp
        }

        noCacheLevel = level
        noCacheMaxLevel = maxLevel
        noCacheRarity = currentRarity
        noCacheMode = false

        if (hadDifferentPet && currentPetName.isNotBlank()) {
            currentPet = getTextureFromNeu(currentPetName, true)
            updatePet()
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

    private fun normalizePetName(rawName: String): String {
        val noFormat = ChatFormatting.stripFormatting(rawName).orEmpty()
        val noSymbols = noFormat
            .replace(" ✦", "")
            .replace("⭐ ", "")
            .replace("⭐", "")
            .trim()
        return removeLevel(noSymbols)
    }

    private fun findPetFromInventory(petName: String, rarity: String) {
        val noSymbol = petName.replace(" ✦", "").replace("⭐ ", "")
        val petNameWithoutLvl = normalizePetName(noSymbol)

        if (petNameWithoutLvl.isNotBlank()) {
            currentPetName = petNameWithoutLvl
            currentRarity = rarity.ifBlank { currentRarity }
        }

        if (noCacheMode) {
            setPet(getTextureFromNeu(petNameWithoutLvl.ifBlank { noSymbol }, true))
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
        val rarityText = getPetRarityText(pet.customName)
        currentRarity = getPetRarity(rarityText)
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
                    currentPetName = normalizePetName(matcher.group(2))
                    currentRarity = rarity
                    pendingTabName = null
                    pendingTabLevel = null
                    pendingTabStableTicks = 0
                    enableFastSync()
                    scheduler.schedule({ readTab(Minecraft.getInstance(), false) }, 120, TimeUnit.MILLISECONDS)
                    scheduler.schedule({ readTab(Minecraft.getInstance(), false) }, 320, TimeUnit.MILLISECONDS)
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
                currentPetName = normalizePetName(autopetPet)
                val parsedLevel = autopetLevel.toIntOrNull() ?: 1
                val parsedMax = if (isGoldenDragon(autopetPet)) 200 else 100
                val matchIndex = string.indexOf(autopetLevel) + autopetLevel.length - 2
                var rarity = "common"

                if (matchIndex!=-1 && matchIndex + 6 <= string.length) {
                    val input = string.substring(matchIndex + 4, matchIndex + 6)
                    val color = colorCodeToName(input)
                    if (color.size > 1) {
                        rarity = color[1]
                    }
                }

                level = parsedLevel
                maxLevel = parsedMax
                xp = 0f
                noCacheLevel = parsedLevel
                noCacheRarity = rarity
                noCacheMaxLevel = parsedMax
                noCacheMode = true
                currentRarity = rarity

                pendingTabName = null
                pendingTabLevel = null
                pendingTabStableTicks = 0
                enableFastSync()

                currentPet = getTextureFromNeu(currentPetName, true)
                updatePet()
                updateStats()

                findPetFromInventory(autopetPet, rarity)
                scheduler.schedule({ readTab(Minecraft.getInstance(), false) }, 120, TimeUnit.MILLISECONDS)
                scheduler.schedule({ readTab(Minecraft.getInstance(), false) }, 320, TimeUnit.MILLISECONDS)
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
                noCacheLevel = level
                xp = 0f
                updateStats()
                levelUp()
                preventTabUpdate()
            }
        }
    }
}