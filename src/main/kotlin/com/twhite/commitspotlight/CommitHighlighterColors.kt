package com.twhite.commitspotlight

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * [light]/[dark] are fully-opaque base colors; the actual alpha applied at render time comes
 * from [CommitHighlighterSettings.alphaPercent], read live in [toJBColor] so changing that one
 * global setting immediately affects every color without needing to regenerate this palette.
 */
data class HighlightColor(
    val id: String,
    val displayName: String,
    val light: Color,
    val dark: Color
) {
    fun toJBColor(): JBColor {
        val alpha = (255 * (CommitHighlighterSettings.getInstance().alphaPercent / 100f)).toInt().coerceIn(0, 255)
        return JBColor(withAlpha(light, alpha), withAlpha(dark, alpha))
    }

    private fun withAlpha(color: Color, alpha: Int): Color = Color(color.red, color.green, color.blue, alpha)
}

private data class Hue(val id: String, val name: String, val angle: Float, val saturationBoost: Float = 0f)

object CommitHighlighterColors {

    // Hue angles on the standard color wheel (degrees), spread with a minimum ~37° gap between
    // any two neighbors (red/orange/yellow used to be packed into a ~50° span and looked alike).
    // Pink gets a saturation boost so it reads as an actual pink rather than a pale rose. Listed
    // alphabetically by name.
    private val HUES = listOf(
        Hue("blue", "Blue", 212f),
        Hue("green", "Green", 125f),
        Hue("orange", "Orange", 30f),
        Hue("pink", "Pink", 305f, saturationBoost = 0.20f),
        Hue("purple", "Purple", 258f),
        Hue("red", "Red", 350f),
        Hue("teal", "Teal", 170f),
        Hue("yellow", "Yellow", 68f),
    )

    // A single vividness tier per hue (previously "Normal"); opacity is now the separately
    // adjustable knob instead of a Light/Normal/Dark preset ladder.
    private const val SAT_LIGHT = 0.58f
    private const val BRI_LIGHT = 0.95f
    private const val SAT_DARK = 0.58f
    private const val BRI_DARK = 0.36f

    val ALL: List<HighlightColor> = HUES.map { hue ->
        HighlightColor(
            id = hue.id,
            displayName = hue.name,
            light = hsb(hue.angle, SAT_LIGHT + hue.saturationBoost, BRI_LIGHT),
            dark = hsb(hue.angle, SAT_DARK + hue.saturationBoost, BRI_DARK)
        )
    }

    val DEFAULT: HighlightColor = ALL.first { it.id == "yellow" }

    fun byId(id: String): HighlightColor = ALL.firstOrNull { it.id == id } ?: DEFAULT

    private fun hsb(hueDegrees: Float, saturation: Float, brightness: Float): Color =
        Color(Color.HSBtoRGB(hueDegrees / 360f, saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f)))
}
