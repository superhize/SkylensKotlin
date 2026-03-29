package org.nextrg.skylens.features

import kotlinx.coroutines.*
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.nextrg.skylens.ModConfig
import org.nextrg.skylens.api.Pets.getCurrentPet
import org.nextrg.skylens.api.Pets.getPetHeldItem
import org.nextrg.skylens.api.Pets.getPetLevel
import org.nextrg.skylens.api.Pets.getPetMaxLevel
import org.nextrg.skylens.api.Pets.getPetRarity
import org.nextrg.skylens.api.Pets.getPetRarityText
import org.nextrg.skylens.api.Pets.getPetXp
import org.nextrg.skylens.features.HudEditor.Companion.hudEditor
import org.nextrg.skylens.helpers.OtherUtil.getTextureFromNeu
import org.nextrg.skylens.helpers.OtherUtil.onSkyblock
import org.nextrg.skylens.helpers.RenderUtil
import org.nextrg.skylens.helpers.RenderUtil.drawItem
import org.nextrg.skylens.helpers.RenderUtil.drawText
import org.nextrg.skylens.helpers.VariablesUtil.animateFloat
import org.nextrg.skylens.helpers.VariablesUtil.getAlphaProgress
import org.nextrg.skylens.helpers.VariablesUtil.getRainbow
import org.nextrg.skylens.helpers.VariablesUtil.quad
import org.nextrg.skylens.helpers.VariablesUtil.sToMs
import org.nextrg.skylens.helpers.VariablesUtil.withAlpha
import org.nextrg.skylens.pipelines.Renderables.drawPie
import org.nextrg.skylens.pipelines.Renderables.drawPieGradient
import org.nextrg.skylens.pipelines.Renderables.roundGradient
import org.nextrg.skylens.pipelines.Renderables.roundRectangleFloat
import java.lang.Math.clamp
import java.util.*
import kotlin.math.max
import kotlin.math.sin

object PetOverlay {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var currentPet: ItemStack = ItemStack(Items.BONE)
    private var heldItem: ItemStack = ItemStack(Items.AIR)

    private var level: Int = 1
    private var maxLevel: Int = 100
    private var xp: Float = 0f
    private var rarity: String = "common"

    private var animatedXp: Float = 0f
    private var animatedLevelProgress: Float = 0f
    private var animatedLevelUp: Float = 0f

    private var transitionDuration = 300L
    private var levelUpDelay = sToMs(2f)

    private var transition = 0f
    private var transitionX = 0f
    private var transitionY = 0f
    private var hidden: Boolean = true

    private var cacheColor1 = ModConfig.petOverlayColor2.rgb
    private var cacheColor2 = ModConfig.petOverlayColor1.rgb
    private var cacheColor3 = ModConfig.petOverlayColor3.rgb

    private var isBarType = false
    private var altStyle = false
    private var flipped = false
    private var invertColor = false
    private var showItem = false
    private var idleAnimPulse = false
    private var idleAnimHover = false
    private var levelUpAnim = false
    private var valueChangeAnim = false
    private var rainbowLevel = false
    private var rainbowXp = false
    private var rainbowBg = false

    private var updatePetScope: Job? = null

    private val rarityColors: Map<String, IntArray> = java.util.Map.of(
        "special", intArrayOf(-0x55dedf, -0xcdce, -0x88eaeb),
        "divine", intArrayOf(-0xf7aa67, -0xee5523, -0xfac99a),
        "mythic", intArrayOf(-0x88dd97, -0xaa01, -0xaeeebf),
        "legendary", intArrayOf(-0x4c8900, -0x35800, -0x9fcb00),
        "epic", intArrayOf(-0xa8fe49, -0x56cd27, -0xdbfeac),
        "rare", intArrayOf(-0xcdcd5d, -0xadad0d, -0xeeeecb),
        "uncommon", intArrayOf(-0xea75eb, -0xab02ac, -0xebc5ec),
        "common", intArrayOf(0xFF9A9A9A.toInt(), 0xFFFFFFFF.toInt(), 0xFF636363.toInt())
    )

    fun updatePet() {
        updatePetScope?.cancel()
        updatePetScope = scope.launch {
            delay((transition * transitionDuration).toLong())

            val pet = getCurrentPet()
            currentPet = pet
            rarity = getPetRarity(getPetRarityText(pet.customName))
            updateTheme()
        }
    }

    fun levelUp() {
        if (!levelUpAnim) return
        scope.launch {
            animateFloat(animatedXp, 1f, transitionDuration / 2, ::quad).collect { animatedXp = it }
            animateFloat(0f, 1f, transitionDuration, ::quad).collect { animatedLevelUp = it }
            delay(levelUpDelay)
            xp = 0f
            animateFloat(1f, 0f, transitionDuration, ::quad).collect { animatedLevelUp = it }
        }
    }

    fun updateStats() {
        if (animatedLevelUp > 0f) return

        scope.launch {
            delay((transition * transitionDuration).toLong())
            heldItem = getTextureFromNeu(getPetHeldItem(), false)

            val newLevel = getPetLevel()
            val newMaxLevel = getPetMaxLevel()
            val newXp = getPetXp()

            level = newLevel
            maxLevel = newMaxLevel
            xp = newXp

            if (ModConfig.petOverlayAnimation_LevelXp) {
                scope.launch {
                    animateFloat(
                        animatedLevelProgress,
                        newLevel.toFloat() / newMaxLevel,
                        transitionDuration,
                        ::quad
                    ).collect {
                        animatedLevelProgress = it
                    }
                }
                scope.launch {
                    animateFloat(animatedXp, newXp, transitionDuration, ::quad).collect {
                        animatedXp = it
                    }
                }
            } else {
                animatedLevelProgress = newLevel.toFloat() / newMaxLevel
                animatedXp = newXp
            }
        }
    }

    private suspend fun transition(show: Boolean) {
        animateFloat(if (show) 0f else 1f, if (show) 1f else 0f, transitionDuration, ::quad).collect { value ->
            transition = value
            transitionX = value
            transitionY = value
        }
    }

    private fun show(show: Boolean, offset: Boolean) {
        scope.launch {
            if (offset) {
                delay(transitionDuration)
                if (show) {
                    updatePet()
                }
            }
            hidden = !show
            transition(show)
        }
    }

    fun showOverlay() {
        if (hidden) {
            show(true, false)
        } else {
            hideOverlay()
            show(true, true)
        }
    }

    fun hideOverlay() {
        if (!hidden) {
            show(false, false)
        }
    }

    fun prepare() {
        updateTheme()
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.HOTBAR,
            Identifier.fromNamespaceAndPath("skylens", "pet-overlay"),
            PetOverlay::prepareRender
        )
    }

    private fun prepareRender(drawContext: GuiGraphics, renderTickCounter: DeltaTracker) {
        render(drawContext)
    }

    fun highlight(context: GuiGraphics) {
        val (x, y) = getPosition()
        val scale = ModConfig.petOverlayScale

        val margin = 1
        val baseWidth = 24 + (if (isBarType) 29 else 0) + margin * 2
        val baseHeight = 25 + margin * 2 + (if (!isBarType) 16 else 0)

        val scaledWidth = (baseWidth * scale).toInt()
        val scaledHeight = (baseHeight * scale).toInt()

        val intX = x.toInt() - (margin * scale).toInt()
        val intY = y.toInt() - (16 * scale).toInt() - (margin * scale).toInt() - if (!isBarType) (16 * scale).toInt() else 0

        context.fill(intX, intY, intX + scaledWidth, intY + scaledHeight, 0x14FFFFFF)
    }

    fun getPosition(): Pair<Float, Float> {
        val scale = ModConfig.petOverlayScale
        val (baseX, baseY) = RenderUtil.computePosition(
            RenderUtil.ElementPos(
                anchorKey = ModConfig.petOverlayAnchor.toString(),
                offsetX = ModConfig.petOverlayX.toFloat(),
                offsetY = ModConfig.petOverlayY.toFloat(),
                isBar = isBarType,
                clampX = { pos, screenW -> clamp(pos, 1f, screenW.toFloat() - (25 + if (isBarType) 27 else 0) * scale) },
                clampY = { pos, screenH -> clamp(pos, (17f + if (!isBarType) 16 else 0) * scale, screenH.toFloat() - 10 * scale) }
            )
        )

        val (screenW, screenH) = RenderUtil.getScaledWidthHeight()
        val (ax, ay) = RenderUtil.anchors[ModConfig.petOverlayAnchor.toString()] ?: floatArrayOf(0.5f, 1f)

        val anchorX = screenW * ax - 50 * ax
        val anchorY = screenH * ay - 8 * ay
        val marginX = 2 * (1 - ax * 2)
        val marginY = 2 * (1 - ay * 2)

        fun map(value: Float): Float = (120f * (value - 0.5f)) * ModConfig.petOverlayScale
        var offsetX = map(ax)
        var offsetY = map(ay)
        offsetX -= (baseX - anchorX - marginX) * (1 - ax * 2)
        offsetY += (baseY - anchorY - marginY) * (1 - ay * 2)

        if (ModConfig.petOverlayAnchor.toString() in listOf("TopMiddle", "BottomMiddle")) {
            transitionX = 1f
        }

        val finalX = baseX + offsetX - offsetX * (if (hudEditor) 1f else transitionX)
        val finalY = baseY + offsetY - offsetY * (if (hudEditor) 1f else transitionY)

        return finalX to finalY
    }

    fun getIdleProgress(time: Double = 1700.0): Float {
        return (Util.getMillis() / time).toFloat() % 1
    }

    fun updateConfigValues() {
        val type = ModConfig.petOverlayType
        isBarType = type.toString().contains("Bar")
        altStyle = type.toString().contains("ALT")
        flipped = ModConfig.petOverlayFlip
        invertColor = ModConfig.petOverlayInvert
        showItem = ModConfig.petOverlayShowItem
        idleAnimPulse = ModConfig.petOverlayAnimation_IdlePulse
        idleAnimHover = ModConfig.petOverlayAnimation_IdleHover
        valueChangeAnim = ModConfig.petOverlayAnimation_LevelXp
        levelUpAnim = ModConfig.petOverlayAnimation_LevelUp
        rainbowLevel = ModConfig.petOverlayRainbowLvl
        rainbowXp = ModConfig.petOverlayRainbowXp
        rainbowBg = ModConfig.petOverlayRainbowBg
        updateTheme()
    }

    private fun updateTheme() {
        val configTheme = ModConfig.petOverlayTheme.toString()
        val isCustom = configTheme == "Custom"

        val displayTheme = when {
            configTheme == "Pet" -> rarity
            isCustom -> rarity
            else -> configTheme
        }

        return if (!isCustom) {
            val colors = rarityColors[displayTheme.lowercase()]
            if (colors != null) {
                cacheColor1 = colors[0]
                cacheColor2 = colors[1]
                cacheColor3 = colors[2]
            } else { }
        } else {
            cacheColor1 = ModConfig.petOverlayColor2.rgb
            cacheColor2 = ModConfig.petOverlayColor1.rgb
            cacheColor3 = ModConfig.petOverlayColor3.rgb
        }
    }

    fun render(guiGraphics: GuiGraphics, isHudEditor: Boolean = false) {
        if (!isHudEditor && (!ModConfig.petOverlay || transition == 0f) || !onSkyblock()) return

        val (x, y) = getPosition()
        var color1 = cacheColor1; var color2 = cacheColor2; val color3 = cacheColor3

        val textColor = color2
        if (invertColor) {
            color1 = color2.also { color2 = color1 }
        }

        var yO = y
        if (idleAnimHover && !hudEditor && transition != 0f) {
            yO += (sin(getIdleProgress(2700.0) * 2 * Math.PI) * 0.7f).toFloat()
        }

        val scale = ModConfig.petOverlayScale
        guiGraphics.pose().pushMatrix()
        guiGraphics.pose().translate(x, yO)
        guiGraphics.pose().scale(scale, scale)
        guiGraphics.pose().translate(-x, -yO)

        if (isBarType) {
            renderBars(guiGraphics, x, yO, color1, color2, color3)
        } else {
            renderCircles(guiGraphics, x, yO, color1, color2, color3)
        }
        renderText(guiGraphics, x, yO, textColor)

        guiGraphics.pose().popMatrix()
    }

    private fun renderText(guiGraphics: GuiGraphics, x: Float, y: Float, color: Int) {
        val isLevelMax = level == maxLevel

        val iconX = x + 3 + (if (!isBarType) 1 else 0) + if (isBarType && flipped) 29 else 0
        val iconY = y - 17 + (if (!isBarType) 4.5f else 0f)

        val textX = x + 34 - (if (!isBarType) 21.5f else 0f) - if (isBarType && flipped) 16 else 0
        val textY = y - 10 - (if (!isBarType) 15.5f else 0f)

        val displayLevel = if (isLevelMax) "LV MX" else "Lvl $level"
        if (!isLevelMax) {
            val displayXp = String.format(Locale.US, (if (animatedXp >= 0.1) "%.1f%%" else "%.2f%%"), animatedXp * 100)
            val levelTextY = textY - (3 * animatedLevelUp)
            drawText(guiGraphics, displayXp, textX, levelTextY,
                withAlpha(color, 10.coerceAtLeast(255 - (animatedLevelUp * 255).toInt())),
                1f, true, true)
            drawText(guiGraphics, "LV UP", textX, levelTextY,
                withAlpha(color, 10.coerceAtLeast((animatedLevelUp * 255).toInt())),
                1f, true, true)
        }

        drawText(guiGraphics, displayLevel, textX - if (isLevelMax) 0.5f else 0f, textY - 6 + if (isLevelMax) 3.5f else 0f,
            withAlpha(color, 10.coerceAtLeast(255 - (animatedLevelUp * 255).toInt())),
            if (isLevelMax) 0.9f else 0.8f, true, true)

        val isPlayerHead = heldItem.itemName.toString().contains("player_head")
        drawItem(guiGraphics, currentPet, iconX, iconY + if (!isBarType && showItem && isPlayerHead) 2 else 0, 1.1f * if (hudEditor) 1f else transition)
        if (showItem) {
            val offsetX = if (isPlayerHead) 0 else 7
            val offsetY = -3 - (if (isBarType) 2 else 0) + (if (isPlayerHead) 0 else 8)
            val scale = 0.8f - if (isPlayerHead) 0f else 0.3f

            drawItem(guiGraphics, heldItem, iconX + offsetX, iconY + offsetY, scale)
        }
    }

    private fun renderBars(guiGraphics: GuiGraphics, x: Float, y: Float, color1: Int, color2: Int, color3: Int) {
        val idleProgress = getIdleProgress()
        if (idleAnimPulse) {
            roundRectangleFloat(
                guiGraphics, x + 2 - idleProgress * 6, y + 2 - idleProgress * 6,
                46 + (idleProgress * 13), 4 + (idleProgress * 12),
                withAlpha(color2, 255 - getAlphaProgress(idleProgress)), 0, (3f + 9f * idleProgress * idleProgress) / (if (altStyle) 2f else 1f), 0f
            )
        }

        renderBarBg(guiGraphics, x, y, color3, idleProgress)
        renderBarLevel(guiGraphics, x, y, color2, idleProgress)
        renderBarXp(guiGraphics, x, y, color1, idleProgress)
    }

    private fun renderCircles(guiGraphics: GuiGraphics, x: Float, y: Float, color1: Int, color2: Int, color3: Int) {
        val idleProgress = getIdleProgress()
        if (idleAnimPulse) {
            val color = withAlpha(color2, 255 - getAlphaProgress(idleProgress))
            drawPie(guiGraphics, x + 12f, y - 4f, 1.01f, 11f + 5f * idleProgress, 0f, color, 0f, 0f)
        }

        renderCircleBg(guiGraphics, x + 12f, y - 4f, color3, idleProgress)
        renderCircleLevel(guiGraphics, x + 12f, y - 4f, color2, idleProgress)
        renderCircleXp(guiGraphics, x + 12f, y - 4f, color1, idleProgress)
    }

    private fun renderCircleBg(guiGraphics: GuiGraphics, x: Float, y: Float, color: Int, idleProgress: Float) {
        val radius = 12.54f
        val progress = 1f
        if (!rainbowBg) {
            drawPie(guiGraphics, x, y, progress, radius, 0f, color, 0f, 0f)
        } else {
            drawPieGradient(guiGraphics, x, y, progress, radius, 0f, getRainbow(8, 0.3f), 0f, idleProgress)
        }
    }

    private fun renderCircleLevel(guiGraphics: GuiGraphics, x: Float, y: Float, color: Int, idleProgress: Float) {
        val outerRadius = 12.5f
        val innerRadius = outerRadius - 2.96f
        val progress = animatedLevelProgress
        if (!rainbowLevel) {
            drawPie(guiGraphics, x, y, progress, outerRadius, innerRadius, color, 0f, 0f)
        } else {
            drawPieGradient(guiGraphics, x, y, progress, outerRadius, innerRadius, getRainbow(8), 0f, idleProgress)
        }
    }

    private fun renderCircleXp(guiGraphics: GuiGraphics, x: Float, y: Float, color: Int, idleProgress: Float) {
        val outerRadius = 10.33f - if (altStyle) 1.33f else 0f
        val innerRadius = 0f
        val progress = animatedXp
        if (!rainbowXp) {
            drawPie(guiGraphics, x, y, progress, outerRadius, innerRadius, color, 0f, 0f)
        } else {
            drawPieGradient(guiGraphics, x, y, progress, outerRadius, innerRadius, getRainbow(8), 0f, idleProgress)
        }
    }

    private fun renderBarBg(guiGraphics: GuiGraphics, x: Float, y: Float, color: Int, idleProgress: Float) {
        val borderRadius = if (altStyle) 2.5f else 4f
        val width = 51f
        val height = 8f
        if (!rainbowBg) {
            roundRectangleFloat(guiGraphics, x, y, width, height, color, 0, borderRadius, 0f)
        } else {
            roundGradient(guiGraphics, x, y, width, height, getRainbow(8, 0.15f), 2, idleProgress, 0, borderRadius, 0f)
        }
    }

    private fun renderBarLevel(guiGraphics: GuiGraphics, x: Float, y: Float, color: Int, idleProgress: Float) {
        val borderRadius = if (altStyle) 2.5f else 4f
        val width = max(8f, (51f * animatedLevelProgress))
        val height = 8f
        if (!rainbowLevel) {
            roundRectangleFloat(guiGraphics, x, y, width, height, color, 0, borderRadius, 0f)
        } else {
            roundGradient(guiGraphics, x, y, width, height, getRainbow(8, 1f), 2, idleProgress, 0, borderRadius, 0f)
        }
    }

    private fun renderBarXp(guiGraphics: GuiGraphics, x: Float, y: Float, color: Int, idleProgress: Float) {
        val borderRadius = if (altStyle) 1.5f else 2f
        val width = max(2f, (47f * animatedXp))
        val height = 4f
        if (!rainbowXp) {
            roundRectangleFloat(guiGraphics, x + 2, y + 2, width, height, color, 0, borderRadius, 0f)
        } else {
            roundGradient(guiGraphics, x + 2, y + 2, width, height, getRainbow(8, 0.5f), 2, idleProgress, 0, borderRadius, 0f)
        }
    }
}