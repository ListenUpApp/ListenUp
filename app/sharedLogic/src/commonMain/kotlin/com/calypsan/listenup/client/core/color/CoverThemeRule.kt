package com.calypsan.listenup.client.core.color

import com.calypsan.listenup.client.domain.model.CoverTheme
import com.calypsan.listenup.client.domain.model.CoverThemeStyle
import com.calypsan.listenup.client.domain.model.CoverThemeTokens
import com.calypsan.listenup.client.domain.model.CoverThemes
import com.materialkolor.blend.Blend
import com.materialkolor.contrast.Contrast
import com.materialkolor.hct.Hct
import com.materialkolor.utils.ColorUtils
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Light or dark: which tones the rule aims for (C5). */
internal enum class CoverAppearance(
    val washTone: Double,
    val washChroma: Double,
    val trackTone: Double,
) {
    Light(washTone = 91.0, washChroma = 6.0, trackTone = 85.0),
    Dark(washTone = 20.0, washChroma = 8.0, trackTone = 30.0),
}

/** Both appearances, or neutral (C3). */
internal fun coverThemesOf(
    swatches: CoverSwatches,
    style: CoverThemeStyle,
): CoverThemes? {
    val light = deriveCoverTheme(swatches, style.light, CoverAppearance.Light) ?: return null
    val dark = deriveCoverTheme(swatches, style.dark, CoverAppearance.Dark) ?: return null
    return CoverThemes(light = light, dark = dark)
}

/**
 * Book Detail spec §2.3 steps 2–6 for one appearance. `null` is neutral: no wash source sits apart from the coral, or
 * the platform's ink would not read on the wash.
 */
internal fun deriveCoverTheme(
    swatches: CoverSwatches,
    tokens: CoverThemeTokens,
    appearance: CoverAppearance,
): CoverTheme? {
    if (swatches.clusters.all { Hct.fromInt(it.argb).tone < NEAR_BLACK_TONE }) return null
    val brandHue = Hct.fromInt(tokens.brandArgb).hue
    val source =
        listOfNotNull(swatches.lightMuted, swatches.lightVibrant, swatches.muted)
            .map { Hct.fromInt(it.argb) }
            .firstOrNull { it.chroma >= GREY_CHROMA && hueDistance(it.hue, brandHue) > CORAL_HUE_GUARD }
            ?: return null
    val wash =
        onPaper(
            wash = Hct.from(source.hue, min(source.chroma, appearance.washChroma), appearance.washTone).toInt(),
            paper = tokens.paperArgb,
            tone = appearance.washTone,
        )
    if (contrastRatio(tokens.inkArgb, wash) < MIN_TEXT_CONTRAST) return null
    // Blend.harmonize keeps the coral's tone, so its contrast with the label barely moves; the guard is for tokens
    // that never cleared it. (A softer cap than MCU's fixed 15° would rotate with Hct.from here instead: Q3.)
    val harmonized = Blend.harmonize(tokens.brandArgb, source.toInt())
    val action =
        if (contrastRatio(tokens.onBrandArgb, harmonized) >= MIN_TEXT_CONTRAST) harmonized else tokens.brandArgb
    return CoverTheme(
        washArgb = wash,
        trackArgb = trackFor(swatches, source, brandHue, appearance),
        actionArgb = action,
        onActionArgb = tokens.onBrandArgb,
    )
}

/**
 * The progress track (C7): the cover's second hue family, the most colourful dominant one, toned for a track; without
 * one, the wash's own hue a step deeper.
 */
private fun trackFor(
    swatches: CoverSwatches,
    source: Hct,
    brandHue: Double,
    appearance: CoverAppearance,
): Int {
    val floor = swatches.totalPopulation * ACCENT_MIN_SHARE
    val accent =
        swatches.clusters
            .filter { it.population >= floor }
            .map { it to Hct.fromInt(it.argb) }
            .filter { (_, hct) ->
                hct.chroma >= GREY_CHROMA &&
                    hueDistance(hct.hue, source.hue) >= ACCENT_HUE_GAP &&
                    hueDistance(hct.hue, brandHue) > CORAL_HUE_GUARD
            }.maxByOrNull { (cluster, hct) -> cluster.population * hct.chroma }
            ?.second
    return if (accent != null) {
        Hct.from(accent.hue, min(accent.chroma, TRACK_MAX_CHROMA), appearance.trackTone).toInt()
    } else {
        Hct.from(source.hue, min(source.chroma, appearance.washChroma), appearance.trackTone).toInt()
    }
}

/** Web mixes the wash into its paper (C8): half and half in sRGB, then back to the wash's tone. */
private fun onPaper(
    wash: Int,
    paper: Int?,
    tone: Double,
): Int {
    paper ?: return wash
    val mixed = Hct.fromInt(mix(wash, paper))
    return Hct.from(mixed.hue, mixed.chroma, tone).toInt()
}

private fun mix(
    a: Int,
    b: Int,
): Int {
    fun channel(shift: Int) = ((((a shr shift) and 0xFF) + ((b shr shift) and 0xFF)) / 2.0).roundToInt()
    return ColorUtils.argbFromRgb(channel(16), channel(8), channel(0))
}

/** WCAG contrast ratio between two colours. */
internal fun contrastRatio(
    a: Int,
    b: Int,
): Double = Contrast.ratioOfTones(ColorUtils.lstarFromArgb(a), ColorUtils.lstarFromArgb(b))

/** The angle between two hues the short way round, 0..180. */
internal fun hueDistance(
    a: Double,
    b: Double,
): Double {
    val raw = abs(a - b) % FULL_TURN
    return min(raw, FULL_TURN - raw)
}

private const val NEAR_BLACK_TONE = 20.0
private const val GREY_CHROMA = 4.0
private const val CORAL_HUE_GUARD = 25.0
private const val ACCENT_HUE_GAP = 45.0
private const val ACCENT_MIN_SHARE = 0.05
private const val TRACK_MAX_CHROMA = 16.0
private const val MIN_TEXT_CONTRAST = 4.5
private const val FULL_TURN = 360.0
