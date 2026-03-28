package org.nextrg.skylens.api

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.nextrg.skylens.features.PetOverlay.hideOverlay
import org.nextrg.skylens.features.PetOverlay.levelUp
import org.nextrg.skylens.features.PetOverlay.showOverlay
import org.nextrg.skylens.features.PetOverlay.updatePet
import org.nextrg.skylens.features.PetOverlay.updateStats
import org.nextrg.skylens.helpers.OtherUtil.getTextureFromNeu
import tech.thatgravyboat.skyblockapi.api.data.SkyBlockRarity
import tech.thatgravyboat.skyblockapi.api.profile.PetsAPI
import kotlin.math.abs

object Pets {
    private var currentPet: ItemStack = ItemStack(Items.BONE)
    private var currentPetName: String? = null
    private var currentRarity: String = "common"

    private var level: Int = 1
    private var maxLevel: Int = 100
    private var xp: Float = 0f
    private var heldItem: String = ""
    private var overlayVisible = false

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            if (client.player == null || client.level == null) {
                if (overlayVisible) {
                    overlayVisible = false
                    hideOverlay()
                }
                return@EndTick
            }
            syncFromSkyblockApi()
        })
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

    private fun syncFromSkyblockApi() {
        val petName = PetsAPI.pet?.trim().orEmpty()
        if (petName.isEmpty()) {
            if (overlayVisible) {
                overlayVisible = false
                hideOverlay()
            }
            return
        }

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
        } else if (!overlayVisible) {
            showOverlay()
            overlayVisible = true
        }

        if (petChanged || statsChanged) {
            level = newLevel
            maxLevel = newMaxLevel
            xp = newXp
            heldItem = ""
            updateStats()
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