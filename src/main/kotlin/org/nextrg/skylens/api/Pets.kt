package org.nextrg.skylens.api

import me.owdding.ktmodules.Module
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.nextrg.skylens.features.PetOverlay.hideOverlay
import org.nextrg.skylens.features.PetOverlay.levelUp
import org.nextrg.skylens.features.PetOverlay.showOverlay
import org.nextrg.skylens.features.PetOverlay.updatePet
import org.nextrg.skylens.features.PetOverlay.updateStats
import org.nextrg.skylens.helpers.OtherUtil.getTextureFromNeu
import org.nextrg.skylens.helpers.OtherUtil.onSkyblock
import tech.thatgravyboat.skyblockapi.api.data.SkyBlockRarity
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.base.predicates.OnlyWidget
import tech.thatgravyboat.skyblockapi.api.events.base.predicates.TimePassed
import tech.thatgravyboat.skyblockapi.api.events.info.TabWidget
import tech.thatgravyboat.skyblockapi.api.events.info.TabWidgetChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.IslandChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.render.RenderWorldEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import tech.thatgravyboat.skyblockapi.api.profile.PetsAPI
import kotlin.math.abs

@Module
object Pets {
    private const val DEBUG_PETS = false
    private const val EMPTY_PET_GRACE_MS = 100L

    private var currentPet: ItemStack = ItemStack(Items.BONE)
    private var currentPetName: String? = null
    private var currentRarity: String = "common"

    private var level: Int = 1
    private var maxLevel: Int = 100
    private var xp: Float = 0f
    private var heldItem: String = ""
    private var overlayVisible = false
    private var lastPetDataAt: Long = 0L

    private fun debug(message: String) {
        if (!DEBUG_PETS) return
        println("[Skylens][PetsDebug] $message")
    }

    @Subscription
    fun onIslandChange(event: IslandChangeEvent) {
        debug("IslandChange old=${event.old} new=${event.new}")
        if (currentPetName != null) {
            lastPetDataAt = System.currentTimeMillis()
        }
    }

    @Subscription
    @OnlyWidget(TabWidget.PET)
    fun onPetWidgetChange(event: TabWidgetChangeEvent) {
        debug("PET widget update empty=${event.isEmpty} lines=${event.new.size} pet=${PetsAPI.pet}")
        syncFromSkyblockApi("pet widget")
    }

    @Subscription(TickEvent::class)
    @TimePassed("1s")
    fun onTick(){
        syncFromSkyblockApi("tick")
    }

    fun getCurrentPet(): ItemStack = currentPet

    fun getPetLevel(): Int = level
    fun getPetMaxLevel(): Int = maxLevel
    fun getPetXp(): Float = xp
    fun getPetHeldItem(): String = heldItem
    fun getCurrentPetRarity(): String = currentRarity

    fun getPetRarity(element: Component): String {
        return currentRarity
    }

    fun getPetRarityText(customName: Component?): Component {
        return Component.literal(currentPetName ?: "")
    }

    fun isGoldenDragon(string: String): Boolean {
        return string.contains(Regex("(Golden|Jade|Rose) Dragon"))
    }

    private fun resetPetState(reason: String) {
        currentPetName = null
        currentRarity = "common"
        currentPet = ItemStack(Items.BONE)
        level = 1
        maxLevel = 100
        xp = 0f
        heldItem = ""
        lastPetDataAt = 0L

        if (overlayVisible) {
            overlayVisible = false
            hideOverlay()
        }

        debug("State reset ($reason)")
    }

    private fun syncFromSkyblockApi(source: String) {
        val petName = PetsAPI.pet?.trim().orEmpty()
        if (petName.isEmpty()) {
            val hasCachedPet = currentPetName != null
            val withinGrace = hasCachedPet && (System.currentTimeMillis() - lastPetDataAt) <= EMPTY_PET_GRACE_MS

            if (withinGrace && onSkyblock()) {
                if (!overlayVisible) {
                    showOverlay()
                    overlayVisible = true
                    debug("Fallback from $source using cached pet=$currentPetName (widget PET absent)")
                }
                return
            }

            if (overlayVisible) {
                overlayVisible = false
                hideOverlay()
                debug("Hide overlay from $source because pet is empty")
            }
            return
        }

        lastPetDataAt = System.currentTimeMillis()

        val newRarity = mapRarity(PetsAPI.rarity)
        val newLevel = PetsAPI.level.coerceAtLeast(1)
        val newMaxLevel = if (isGoldenDragon(petName)) 200 else 100
        val newXp = when {
            PetsAPI.isMaxLevel || newLevel >= newMaxLevel -> 1f
            PetsAPI.xpToNextLevel <= 0.0 -> 0f
            else -> (PetsAPI.xp / PetsAPI.xpToNextLevel).toFloat().coerceIn(0f, 1f)
        }

        val petChanged = currentPetName != petName || currentRarity != newRarity
        val statsChanged = level != newLevel || maxLevel != newMaxLevel || abs(xp - newXp) > 0.0001f
        val leveledUp = !petChanged && newLevel > level

        if (petChanged) {
            currentPetName = petName
            currentRarity = newRarity
            currentPet = getTextureFromNeu(petName, true)
            updatePet()
            showOverlay()
            overlayVisible = true
            debug("Pet changed from $source -> $petName/$newRarity lvl=$newLevel xp=$newXp")
        } else if (!overlayVisible) {
            showOverlay()
            overlayVisible = true
            debug("Show overlay from $source with existing pet=$petName")
        }

        if (petChanged || statsChanged) {
            level = newLevel
            maxLevel = newMaxLevel
            xp = newXp
            heldItem = ""
            updateStats()
            if (statsChanged && !petChanged) {
                debug("Stats update from $source lvl=$newLevel/$newMaxLevel xp=$newXp")
            }
        }

        if (leveledUp) {
            levelUp()
        }
    }

    private fun mapRarity(rarity: SkyBlockRarity?): String {
        return when (rarity) {
            SkyBlockRarity.UNCOMMON -> "uncommon"
            SkyBlockRarity.RARE -> "rare"
            SkyBlockRarity.EPIC -> "epic"
            SkyBlockRarity.LEGENDARY -> "legendary"
            SkyBlockRarity.MYTHIC -> "mythic"
            SkyBlockRarity.DIVINE -> "divine"
            SkyBlockRarity.SPECIAL,
            SkyBlockRarity.VERY_SPECIAL,
            SkyBlockRarity.ULTIMATE,
            SkyBlockRarity.ADMIN -> "special"
            else -> "common"
        }
    }
}