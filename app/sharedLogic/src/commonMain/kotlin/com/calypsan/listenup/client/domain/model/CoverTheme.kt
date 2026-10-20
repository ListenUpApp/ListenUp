package com.calypsan.listenup.client.domain.model

/**
 * One appearance's colours derived from a book's cover (Book Detail spec §2.3), as ARGB.
 *
 * [washArgb] is the hero band; [trackArgb] the progress track; [actionArgb] the action coral harmonised toward the
 * cover; [onActionArgb] the label ink that reads on it (always the platform's own on-brand ink, at ≥ 4.5:1).
 */
data class CoverTheme(
    val washArgb: Int,
    val trackArgb: Int,
    val actionArgb: Int,
    val onActionArgb: Int,
)

/**
 * A cover's [CoverTheme] for light and for dark appearance, derived together so a switch between them is instant.
 * Both or neither: a cover with no wash in one appearance is neutral in both.
 */
data class CoverThemes(
    val light: CoverTheme,
    val dark: CoverTheme,
)

/**
 * What one appearance derives against: the platform's action coral [brandArgb] and its label ink [onBrandArgb], the
 * body ink [inkArgb] that must read on the wash, and — web only — the [paperArgb] the wash is mixed into (spec §2.3
 * step 3). Native platforms sit the wash on their system background and pass `null`.
 */
data class CoverThemeTokens(
    val brandArgb: Int,
    val onBrandArgb: Int,
    val inkArgb: Int,
    val paperArgb: Int?,
)

/**
 * A platform's [CoverThemeTokens] for both appearances. iOS builds it from its asset catalog; web from its
 * stylesheet's tokens. Android has none: its surfaces stay neutral under Material You.
 */
data class CoverThemeStyle(
    val light: CoverThemeTokens,
    val dark: CoverThemeTokens,
)
