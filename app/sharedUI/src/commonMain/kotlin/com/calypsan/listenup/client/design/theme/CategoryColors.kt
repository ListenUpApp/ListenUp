package com.calypsan.listenup.client.design.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * A tonal fill and the ink drawn on it — the two colours a category chip or icon tile needs.
 * [content] on [container] clears WCAG AA for text (pinned by `CategoryColorsContrastTest`), so
 * a label or glyph reads whatever surface the pair sits on.
 *
 * @property container The fill behind the label or glyph.
 * @property content The label or glyph colour.
 */
@Immutable
data class CategoryTone(
    val container: Color,
    val content: Color,
)

/**
 * A categorical colour — a device type, a licence family — with a composed tone for each theme.
 * Category colour is identity rather than a role, so it keeps its hue under dynamic colour; what
 * changes between light and dark is the tone, hand-picked in OKLCH at the same hue so the dark
 * pair is designed rather than inverted.
 *
 * @property light The tone used under the light theme.
 * @property dark The tone used under the dark theme.
 */
@Immutable
data class CategoryColor(
    val light: CategoryTone,
    val dark: CategoryTone,
) {
    /** The tone for the theme currently in effect. */
    val current: CategoryTone
        @Composable
        @ReadOnlyComposable
        get() = if (LocalDarkTheme.current) dark else light
}

/**
 * The shared categorical palette. Each entry is one hue at two tones: a light-theme container near
 * OKLCH L 0.93 with ink near L 0.45, and a dark-theme container near L 0.33 with ink near L 0.88.
 * Distinct hues keep neighbouring categories apart; every consumer also carries a text label or a
 * glyph, so colour is never the only cue.
 */
object CategoryPalette {
    /** Blue — phones, Apache-2.0. */
    val Blue =
        categoryColor(
            lightContent = 0xFF1F52A1,
            lightContainer = 0xFFD6E9FF,
            darkContent = 0xFFBCD9FF,
            darkContainer = 0xFF223554,
        )

    /** Sky — desktops and laptops. */
    val Sky =
        categoryColor(
            lightContent = 0xFF005992,
            lightContainer = 0xFFD0ECFF,
            darkContent = 0xFFB2DDFF,
            darkContainer = 0xFF183852,
        )

    /** Periwinkle — BSD-2-Clause. */
    val Periwinkle =
        categoryColor(
            lightContent = 0xFF374DA2,
            lightContainer = 0xFFDCE7FF,
            darkContent = 0xFFC5D6FF,
            darkContainer = 0xFF293354,
        )

    /** Violet — tablets, BSD-3-Clause. */
    val Violet =
        categoryColor(
            lightContent = 0xFF52449E,
            lightContainer = 0xFFE5E4FF,
            darkContent = 0xFFD3D1FF,
            darkContainer = 0xFF333053,
        )

    /** Green — cast targets, MIT. */
    val Green =
        categoryColor(
            lightContent = 0xFF00683C,
            lightContainer = 0xFFD0F2DD,
            darkContent = 0xFFB1E6C7,
            darkContainer = 0xFF143F2A,
        )

    /** Teal — ISC. */
    val Teal =
        categoryColor(
            lightContent = 0xFF006465,
            lightContainer = 0xFFC6F2F1,
            darkContent = 0xFFA0E7E6,
            darkContainer = 0xFF003F3F,
        )

    /** Orange — speakers, LGPL. */
    val Orange =
        categoryColor(
            lightContent = 0xFF923100,
            lightContainer = 0xFFFFDFD1,
            darkContent = 0xFFFFC9B4,
            darkContainer = 0xFF4F2A1C,
        )

    /** Red — GPL-3.0. */
    val Red =
        categoryColor(
            lightContent = 0xFF932B2B,
            lightContainer = 0xFFFFDDD9,
            darkContent = 0xFFFFC6C0,
            darkContainer = 0xFF502825,
        )

    /** Magenta — OFL-1.1. */
    val Magenta =
        categoryColor(
            lightContent = 0xFF862E6B,
            lightContainer = 0xFFFEDDF1,
            darkContent = 0xFFF9C6E5,
            darkContainer = 0xFF4A283E,
        )

    /** Neutral — the unknown or unrecognised category. */
    val Neutral =
        categoryColor(
            lightContent = 0xFF555555,
            lightContainer = 0xFFE8E8E8,
            darkContent = 0xFFD7D7D7,
            darkContainer = 0xFF353535,
        )

    /** Every palette entry, for exhaustive contrast checks. */
    val all: List<CategoryColor> = listOf(Blue, Sky, Periwinkle, Violet, Green, Teal, Orange, Red, Magenta, Neutral)
}

private fun categoryColor(
    lightContent: Long,
    lightContainer: Long,
    darkContent: Long,
    darkContainer: Long,
) = CategoryColor(
    light = CategoryTone(container = Color(lightContainer), content = Color(lightContent)),
    dark = CategoryTone(container = Color(darkContainer), content = Color(darkContent)),
)
