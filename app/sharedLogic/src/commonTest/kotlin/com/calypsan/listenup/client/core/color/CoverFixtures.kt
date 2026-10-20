package com.calypsan.listenup.client.core.color

import com.calypsan.listenup.client.domain.model.CoverThemeStyle
import com.calypsan.listenup.client.domain.model.CoverThemeTokens
import com.materialkolor.utils.ColorUtils
import kotlin.math.abs
import kotlin.math.sqrt

/** `"#RRGGBB"` as opaque ARGB. */
internal fun hex(rgb: String): Int = (0xFF000000L or rgb.removePrefix("#").toLong(16)).toInt()

/** A cover made of [parts] (colour, pixel count), one row of pixels. */
internal fun coverOf(vararg parts: Pair<String, Int>): IntArray =
    parts.flatMap { (rgb, count) -> List(count) { hex(rgb) } }.toIntArray()

internal fun swatchesOf(vararg parts: Pair<String, Int>): CoverSwatches {
    val pixels = coverOf(*parts)
    return requireNotNull(extractSwatches(pixels, width = pixels.size, height = 1))
}

/** The Way of Kings, sampled from the real cover on the `BookDetail-*-Covers` boards. */
internal val WAY_OF_KINGS =
    arrayOf("#AEB5C2" to 1300, "#4B4465" to 900, "#50699B" to 700, "#E2A159" to 600, "#748AB3" to 400, "#A85C50" to 196)

/** The boards' near-coral placeholder: every swatch sits beside coral. */
internal val NEAR_CORAL =
    arrayOf("#C4532E" to 1400, "#9A5A44" to 900, "#5A2A1E" to 800, "#E08A5C" to 600, "#D9B7A4" to 396)

/** The boards' deep-green placeholder: one hue family, sage the light muted. */
internal val GREEN = arrayOf("#1F3229" to 1300, "#5F7A6A" to 1000, "#3F7A55" to 900, "#A9BCAF" to 896)

internal val GREYSCALE = arrayOf("#202020" to 1400, "#808080" to 1400, "#C8C8C8" to 1296)

internal val NEAR_BLACK = arrayOf("#0A0A0F" to 2000, "#15121A" to 1500, "#1E1410" to 596)

/** A near-black cover with a small red corner and a slate title (The Art of War on the rig): about 93% dark. */
internal val ART_OF_WAR =
    arrayOf("#0A0A0F" to 2000, "#15121A" to 1500, "#1E1410" to 300, "#B3261E" to 150, "#5A6470" to 146)

/** `#RRGGBB`, for clues. */
internal fun cssLike(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

/** iOS's tokens (asset catalog `BrandCoral` / `OnBrandCoral`, `.label`), as `CoverThemeStyle.listenUp` builds them. */
internal val IOS_STYLE =
    CoverThemeStyle(
        light =
            CoverThemeTokens(
                brandArgb = hex("#D73812"),
                onBrandArgb = hex("#FFFFFF"),
                inkArgb = hex("#000000"),
                paperArgb = null,
            ),
        dark =
            CoverThemeTokens(
                brandArgb = hex("#FF6A3D"),
                onBrandArgb = hex("#1C0A04"),
                inkArgb = hex("#FFFFFF"),
                paperArgb = null,
            ),
    )

/** Web's tokens (`--coral-fill`, `--on-coral`, `--ink`, `--paper` in both themes), as `WebCoverThemeStyle` holds them. */
internal val WEB_STYLE =
    CoverThemeStyle(
        light =
            CoverThemeTokens(
                brandArgb = hex("#D73812"),
                onBrandArgb = hex("#FFFFFF"),
                inkArgb = hex("#1C1714"),
                paperArgb = hex("#F5F0E8"),
            ),
        dark =
            CoverThemeTokens(
                brandArgb = hex("#D73812"),
                onBrandArgb = hex("#FFFFFF"),
                inkArgb = hex("#F3EEE8"),
                paperArgb = hex("#13110F"),
            ),
    )

/** CIE76 ΔE between two colours: under ~2 reads as the same colour, under ~6 as the same family. */
internal fun deltaE(
    a: Int,
    b: Int,
): Double {
    val la = ColorUtils.labFromArgb(a)
    val lb = ColorUtils.labFromArgb(b)
    return sqrt((0..2).sumOf { (la[it] - lb[it]).let { d -> d * d } })
}

/** Per-channel distance: the quantizer averages in L*a*b*, so a flat colour can come back one step off. */
internal fun channelDistance(
    a: Int,
    b: Int,
): Int = listOf(16, 8, 0).maxOf { shift -> abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) }
