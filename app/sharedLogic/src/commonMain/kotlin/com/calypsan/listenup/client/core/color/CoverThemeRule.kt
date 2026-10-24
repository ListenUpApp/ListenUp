package com.calypsan.listenup.client.core.color

import com.calypsan.listenup.client.domain.model.CoverTheme
import com.calypsan.listenup.client.domain.model.CoverThemeStyle
import com.calypsan.listenup.client.domain.model.CoverThemeTokens
import com.calypsan.listenup.client.domain.model.CoverThemes
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
    if (darkShare(swatches) >= NEAR_BLACK_SHARE) return null
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
    // Harmonising keeps the coral's tone, so its contrast with the label barely moves; the guard is for tokens that
    // never cleared it.
    val harmonized = harmonize(tokens.brandArgb, source)
    val action =
        if (contrastRatio(tokens.onBrandArgb, harmonized) >= MIN_TEXT_CONTRAST) harmonized else tokens.brandArgb
    return CoverTheme(
        washArgb = wash,
        trackArgb =
            trackFor(swatches = swatches, source = source, brandHue = brandHue, appearance = appearance, fill = action),
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
    fill: Int,
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
    val tone = trackTone(appearance, fill)
    return if (accent != null) {
        Hct.from(accent.hue, min(accent.chroma, TRACK_MAX_CHROMA), tone).toInt()
    } else {
        Hct.from(source.hue, min(source.chroma, appearance.washChroma), tone).toInt()
    }
}

/**
 * The appearance's track tone, moved away from the [fill] only as far as WCAG 1.4.11 needs for the bar to read (3:1):
 * lighter in light, deeper in dark. Web's dark fill is Coral Fill, which sits too close to tone 30.
 */
private fun trackTone(
    appearance: CoverAppearance,
    fill: Int,
): Double {
    val fillTone = ColorUtils.lstarFromArgb(fill)
    if (Contrast.ratioOfTones(fillTone, appearance.trackTone) >= MIN_GRAPHIC_CONTRAST) return appearance.trackTone
    val moved =
        when (appearance) {
            CoverAppearance.Light -> Contrast.lighter(fillTone, MIN_GRAPHIC_CONTRAST)
            CoverAppearance.Dark -> Contrast.darker(fillTone, MIN_GRAPHIC_CONTRAST)
        }
    return moved ?: appearance.trackTone
}

/** The share of the cover's pixels darker than tone 20: a cover that is nearly all black has no colour to give. */
private fun darkShare(swatches: CoverSwatches): Double =
    swatches.clusters
        .filter { Hct.fromInt(it.argb).tone < NEAR_BLACK_TONE }
        .sumOf { it.population }
        .toDouble() / swatches.totalPopulation

/**
 * MCU's `Blend.harmonize` with a gentler cap (Q3): the coral turns half-way toward the cover's hue, at most
 * [HARMONIZE_CAP_DEGREES], keeping its chroma and tone. The boards turned it a few degrees, never MCU's 15.
 */
private fun harmonize(
    design: Int,
    toward: Hct,
): Int {
    val from = Hct.fromInt(design)
    val rotation = min(hueDistance(from.hue, toward.hue) * HALF, HARMONIZE_CAP_DEGREES)
    val clockwise = ((toward.hue - from.hue) % FULL_TURN + FULL_TURN) % FULL_TURN <= HALF_TURN
    val hue = ((from.hue + if (clockwise) rotation else -rotation) % FULL_TURN + FULL_TURN) % FULL_TURN
    return Hct.from(hue, from.chroma, from.tone).toInt()
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
    fun channel(shift: Int) = ((((a shr shift) and CHANNEL) + ((b shr shift) and CHANNEL)) / 2.0).roundToInt()
    return ColorUtils.argbFromRgb(channel(RED_SHIFT), channel(GREEN_SHIFT), channel(BLUE_SHIFT))
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
private const val NEAR_BLACK_SHARE = 0.9
private const val MIN_GRAPHIC_CONTRAST = 3.0
private const val HALF = 0.5
private const val HALF_TURN = 180.0
private const val GREY_CHROMA = 4.0
private const val CORAL_HUE_GUARD = 25.0
private const val ACCENT_HUE_GAP = 45.0
private const val ACCENT_MIN_SHARE = 0.05
private const val TRACK_MAX_CHROMA = 16.0
private const val MIN_TEXT_CONTRAST = 4.5
private const val FULL_TURN = 360.0
private const val CHANNEL = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val BLUE_SHIFT = 0

/** How far the action coral turns toward the cover, at most (Q3). */
internal const val HARMONIZE_CAP_DEGREES = 5.0
