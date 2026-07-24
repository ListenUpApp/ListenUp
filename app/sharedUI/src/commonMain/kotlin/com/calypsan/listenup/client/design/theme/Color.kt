package com.calypsan.listenup.client.design.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * ListenUp brand color — the vivid coral (#F0512F) used for primary actions, the brand
 * mark, FABs, and focused field accents. This is the [primary] of the fallback scheme;
 * when Material You (dynamic color) is active it is replaced by the wallpaper-derived hue.
 *
 * Values below mirror the Material 3 Expressive token sheet the design mocks are built from
 * (warm coral primary, warm-neutral surfaces, amber-gold tertiary, blue-grey muted ink).
 */
val ListenUpOrange = Color(0xFFF0512F)

// =============================================================================
// LIGHT — M3 Expressive tokens, coral brand seed
// =============================================================================

private val md_theme_light_primary = Color(0xFFF0512F)
private val md_theme_light_onPrimary = Color(0xFFFFFFFF)
private val md_theme_light_primaryContainer = Color(0xFFFFDBD0)
private val md_theme_light_onPrimaryContainer = Color(0xFF3A0A00)

private val md_theme_light_secondary = Color(0xFF77574D)
private val md_theme_light_onSecondary = Color(0xFFFFFFFF)
private val md_theme_light_secondaryContainer = Color(0xFFFFDBCF)
private val md_theme_light_onSecondaryContainer = Color(0xFF2C150D)

// Amber-gold tertiary (badges / accents)
private val md_theme_light_tertiary = Color(0xFF785A00)
private val md_theme_light_onTertiary = Color(0xFFFFFFFF)
private val md_theme_light_tertiaryContainer = Color(0xFFFFE08A)
private val md_theme_light_onTertiaryContainer = Color(0xFF251A00)

private val md_theme_light_error = Color(0xFFBA1A1A)
private val md_theme_light_onError = Color(0xFFFFFFFF)
private val md_theme_light_errorContainer = Color(0xFFFFDAD6)
private val md_theme_light_onErrorContainer = Color(0xFF410002)

private val md_theme_light_background = Color(0xFFFFF8F6)
private val md_theme_light_onBackground = Color(0xFF231917)
private val md_theme_light_surface = Color(0xFFFFF8F6)
private val md_theme_light_onSurface = Color(0xFF231917)
private val md_theme_light_surfaceVariant = Color(0xFFF1DDD6)

// ListenUp keeps a blue-grey as the muted ink (distinct from the warm surfaces)
private val md_theme_light_onSurfaceVariant = Color(0xFF5B6B86)

private val md_theme_light_surfaceDim = Color(0xFFEDD9D2)
private val md_theme_light_surfaceBright = Color(0xFFFFF8F6)
private val md_theme_light_surfaceContainerLowest = Color(0xFFFFFFFF)
private val md_theme_light_surfaceContainerLow = Color(0xFFFFF1EC)
private val md_theme_light_surfaceContainer = Color(0xFFFCEAE3)
private val md_theme_light_surfaceContainerHigh = Color(0xFFF7E3DC)
private val md_theme_light_surfaceContainerHighest = Color(0xFFF1DDD6)

private val md_theme_light_outline = Color(0xFFA08C84)
private val md_theme_light_outlineVariant = Color(0xFFE3D0C9)
private val md_theme_light_inverseSurface = Color(0xFF362F2D)
private val md_theme_light_inverseOnSurface = Color(0xFFFBEEEB)
private val md_theme_light_inversePrimary = Color(0xFFFFB4A0)
private val md_theme_light_scrim = Color(0xFF000000)

// =============================================================================
// DARK — M3 Expressive tokens
// =============================================================================

private val md_theme_dark_primary = Color(0xFFFF6A3D)
private val md_theme_dark_onPrimary = Color(0xFF471000)
private val md_theme_dark_primaryContainer = Color(0xFFA8331A)
private val md_theme_dark_onPrimaryContainer = Color(0xFFFFDBD0)

private val md_theme_dark_secondary = Color(0xFFE7BDB1)
private val md_theme_dark_onSecondary = Color(0xFF442A21)
private val md_theme_dark_secondaryContainer = Color(0xFF5D4037)
private val md_theme_dark_onSecondaryContainer = Color(0xFFFFDBCF)

private val md_theme_dark_tertiary = Color(0xFFECC248)
private val md_theme_dark_onTertiary = Color(0xFF3F2E00)
private val md_theme_dark_tertiaryContainer = Color(0xFF5B4400)
private val md_theme_dark_onTertiaryContainer = Color(0xFFFFE08A)

private val md_theme_dark_error = Color(0xFFFFB4AB)
private val md_theme_dark_onError = Color(0xFF690005)
private val md_theme_dark_errorContainer = Color(0xFF93000A)
private val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)

private val md_theme_dark_background = Color(0xFF1A1110)
private val md_theme_dark_onBackground = Color(0xFFF1DFD9)
private val md_theme_dark_surface = Color(0xFF1A1110)
private val md_theme_dark_onSurface = Color(0xFFF1DFD9)
private val md_theme_dark_surfaceVariant = Color(0xFF53433E)
private val md_theme_dark_onSurfaceVariant = Color(0xFFB6A9C5)

private val md_theme_dark_surfaceDim = Color(0xFF1A1110)
private val md_theme_dark_surfaceBright = Color(0xFF423734)
private val md_theme_dark_surfaceContainerLowest = Color(0xFF140B0A)
private val md_theme_dark_surfaceContainerLow = Color(0xFF231917)
private val md_theme_dark_surfaceContainer = Color(0xFF271D1B)
private val md_theme_dark_surfaceContainerHigh = Color(0xFF322825)
private val md_theme_dark_surfaceContainerHighest = Color(0xFF3D322F)

private val md_theme_dark_outline = Color(0xFF9F8D87)
private val md_theme_dark_outlineVariant = Color(0xFF53433E)
private val md_theme_dark_inverseSurface = Color(0xFFF1DFD9)
private val md_theme_dark_inverseOnSurface = Color(0xFF362F2D)
private val md_theme_dark_inversePrimary = Color(0xFFC03A1E)
private val md_theme_dark_scrim = Color(0xFF000000)

// =============================================================================
// COMPOSED COLOR SCHEMES (fallback — replaced by dynamic color when Material You is active)
// =============================================================================

internal val LightColorScheme =
    lightColorScheme(
        primary = md_theme_light_primary,
        onPrimary = md_theme_light_onPrimary,
        primaryContainer = md_theme_light_primaryContainer,
        onPrimaryContainer = md_theme_light_onPrimaryContainer,
        secondary = md_theme_light_secondary,
        onSecondary = md_theme_light_onSecondary,
        secondaryContainer = md_theme_light_secondaryContainer,
        onSecondaryContainer = md_theme_light_onSecondaryContainer,
        tertiary = md_theme_light_tertiary,
        onTertiary = md_theme_light_onTertiary,
        tertiaryContainer = md_theme_light_tertiaryContainer,
        onTertiaryContainer = md_theme_light_onTertiaryContainer,
        error = md_theme_light_error,
        onError = md_theme_light_onError,
        errorContainer = md_theme_light_errorContainer,
        onErrorContainer = md_theme_light_onErrorContainer,
        background = md_theme_light_background,
        onBackground = md_theme_light_onBackground,
        surface = md_theme_light_surface,
        onSurface = md_theme_light_onSurface,
        surfaceVariant = md_theme_light_surfaceVariant,
        onSurfaceVariant = md_theme_light_onSurfaceVariant,
        outline = md_theme_light_outline,
        outlineVariant = md_theme_light_outlineVariant,
        inverseSurface = md_theme_light_inverseSurface,
        inverseOnSurface = md_theme_light_inverseOnSurface,
        inversePrimary = md_theme_light_inversePrimary,
        scrim = md_theme_light_scrim,
        surfaceDim = md_theme_light_surfaceDim,
        surfaceBright = md_theme_light_surfaceBright,
        surfaceContainerLowest = md_theme_light_surfaceContainerLowest,
        surfaceContainerLow = md_theme_light_surfaceContainerLow,
        surfaceContainer = md_theme_light_surfaceContainer,
        surfaceContainerHigh = md_theme_light_surfaceContainerHigh,
        surfaceContainerHighest = md_theme_light_surfaceContainerHighest,
    )

internal val DarkColorScheme =
    darkColorScheme(
        primary = md_theme_dark_primary,
        onPrimary = md_theme_dark_onPrimary,
        primaryContainer = md_theme_dark_primaryContainer,
        onPrimaryContainer = md_theme_dark_onPrimaryContainer,
        secondary = md_theme_dark_secondary,
        onSecondary = md_theme_dark_onSecondary,
        secondaryContainer = md_theme_dark_secondaryContainer,
        onSecondaryContainer = md_theme_dark_onSecondaryContainer,
        tertiary = md_theme_dark_tertiary,
        onTertiary = md_theme_dark_onTertiary,
        tertiaryContainer = md_theme_dark_tertiaryContainer,
        onTertiaryContainer = md_theme_dark_onTertiaryContainer,
        error = md_theme_dark_error,
        onError = md_theme_dark_onError,
        errorContainer = md_theme_dark_errorContainer,
        onErrorContainer = md_theme_dark_onErrorContainer,
        background = md_theme_dark_background,
        onBackground = md_theme_dark_onBackground,
        surface = md_theme_dark_surface,
        onSurface = md_theme_dark_onSurface,
        surfaceVariant = md_theme_dark_surfaceVariant,
        onSurfaceVariant = md_theme_dark_onSurfaceVariant,
        outline = md_theme_dark_outline,
        outlineVariant = md_theme_dark_outlineVariant,
        inverseSurface = md_theme_dark_inverseSurface,
        inverseOnSurface = md_theme_dark_inverseOnSurface,
        inversePrimary = md_theme_dark_inversePrimary,
        scrim = md_theme_dark_scrim,
        surfaceDim = md_theme_dark_surfaceDim,
        surfaceBright = md_theme_dark_surfaceBright,
        surfaceContainerLowest = md_theme_dark_surfaceContainerLowest,
        surfaceContainerLow = md_theme_dark_surfaceContainerLow,
        surfaceContainer = md_theme_dark_surfaceContainer,
        surfaceContainerHigh = md_theme_dark_surfaceContainerHigh,
        surfaceContainerHighest = md_theme_dark_surfaceContainerHighest,
    )
