package com.calypsan.listenup.client.design.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * ListenUp brand color - coral/orange-red (#F05A3B).
 * Used as the seed color for the color scheme.
 */
val ListenUpOrange = Color(0xFFF05A3B)

// =============================================================================
// LIGHT THEME COLOR PALETTE
// Generated from seed color #F05A3B using Material 3 tonal palette guidelines
// =============================================================================

// Primary tonal values (warm coral) - using brand color #F05A3B
private val md_theme_light_primary = Color(0xFFF05A3B)
private val md_theme_light_onPrimary = Color(0xFFFFFFFF)
private val md_theme_light_primaryContainer = Color(0xFFFFDAD2)
private val md_theme_light_onPrimaryContainer = Color(0xFF410100)

// Secondary tonal values (muted warm)
private val md_theme_light_secondary = Color(0xFF775750)
private val md_theme_light_onSecondary = Color(0xFFFFFFFF)
private val md_theme_light_secondaryContainer = Color(0xFFFFDAD2)
private val md_theme_light_onSecondaryContainer = Color(0xFF2C1511)

// Tertiary tonal values (complementary warm gold)
private val md_theme_light_tertiary = Color(0xFF6F5C2E)
private val md_theme_light_onTertiary = Color(0xFFFFFFFF)
private val md_theme_light_tertiaryContainer = Color(0xFFFADFA6)
private val md_theme_light_onTertiaryContainer = Color(0xFF261A00)

// Error colors
private val md_theme_light_error = Color(0xFFBA1A1A)
private val md_theme_light_onError = Color(0xFFFFFFFF)
private val md_theme_light_errorContainer = Color(0xFFFFDAD6)
private val md_theme_light_onErrorContainer = Color(0xFF410002)

// Background and surface
private val md_theme_light_background = Color(0xFFFFFBFF)
private val md_theme_light_onBackground = Color(0xFF201A19)
private val md_theme_light_surface = Color(0xFFFFF8F6)
private val md_theme_light_onSurface = Color(0xFF201A19)
private val md_theme_light_surfaceVariant = Color(0xFFF5DDD8)
private val md_theme_light_onSurfaceVariant = Color(0xFF534340)

// Surface containers (tinted with primary)
private val md_theme_light_surfaceContainerLowest = Color(0xFFFFFFFF)
private val md_theme_light_surfaceContainerLow = Color(0xFFFFF1EE)
private val md_theme_light_surfaceContainer = Color(0xFFFFEDE9)
private val md_theme_light_surfaceContainerHigh = Color(0xFFFFE4DE)
private val md_theme_light_surfaceContainerHighest = Color(0xFFFFDAD2)

// Outline and inverse
private val md_theme_light_outline = Color(0xFF85736F)
private val md_theme_light_outlineVariant = Color(0xFFD8C2BC)
private val md_theme_light_inverseSurface = Color(0xFF362F2D)
private val md_theme_light_inverseOnSurface = Color(0xFFFBEEEB)
private val md_theme_light_inversePrimary = Color(0xFFFFB4A2)

// =============================================================================
// DARK THEME COLOR PALETTE
// =============================================================================

// Primary tonal values
private val md_theme_dark_primary = Color(0xFFFFB4A2)
private val md_theme_dark_onPrimary = Color(0xFF680F00)
private val md_theme_dark_primaryContainer = Color(0xFF922509)
private val md_theme_dark_onPrimaryContainer = Color(0xFFFFDAD2)

// Secondary tonal values
private val md_theme_dark_secondary = Color(0xFFE7BDB4)
private val md_theme_dark_onSecondary = Color(0xFF442A24)
private val md_theme_dark_secondaryContainer = Color(0xFF5D3F39)
private val md_theme_dark_onSecondaryContainer = Color(0xFFFFDAD2)

// Tertiary tonal values
private val md_theme_dark_tertiary = Color(0xFFDDC38C)
private val md_theme_dark_onTertiary = Color(0xFF3E2E04)
private val md_theme_dark_tertiaryContainer = Color(0xFF564519)
private val md_theme_dark_onTertiaryContainer = Color(0xFFFADFA6)

// Error colors
private val md_theme_dark_error = Color(0xFFFFB4AB)
private val md_theme_dark_onError = Color(0xFF690005)
private val md_theme_dark_errorContainer = Color(0xFF93000A)
private val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)

// Background and surface
private val md_theme_dark_background = Color(0xFF201A19)
private val md_theme_dark_onBackground = Color(0xFFEDE0DD)
private val md_theme_dark_surface = Color(0xFF181210)
private val md_theme_dark_onSurface = Color(0xFFEDE0DD)
private val md_theme_dark_surfaceVariant = Color(0xFF534340)
private val md_theme_dark_onSurfaceVariant = Color(0xFFD8C2BC)

// Surface containers (tinted with primary)
private val md_theme_dark_surfaceContainerLowest = Color(0xFF120D0B)
private val md_theme_dark_surfaceContainerLow = Color(0xFF201A18)
private val md_theme_dark_surfaceContainer = Color(0xFF251E1C)
private val md_theme_dark_surfaceContainerHigh = Color(0xFF302826)
private val md_theme_dark_surfaceContainerHighest = Color(0xFF3B3331)

// Outline and inverse
private val md_theme_dark_outline = Color(0xFFA08C87)
private val md_theme_dark_outlineVariant = Color(0xFF534340)
private val md_theme_dark_inverseSurface = Color(0xFFEDE0DD)
private val md_theme_dark_inverseOnSurface = Color(0xFF362F2D)
private val md_theme_dark_inversePrimary = Color(0xFFC03E1F)

// =============================================================================
// COMPOSED COLOR SCHEMES
// =============================================================================

/**
 * Light color scheme with ListenUp coral as seed color.
 */
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
        surfaceContainerLowest = md_theme_light_surfaceContainerLowest,
        surfaceContainerLow = md_theme_light_surfaceContainerLow,
        surfaceContainer = md_theme_light_surfaceContainer,
        surfaceContainerHigh = md_theme_light_surfaceContainerHigh,
        surfaceContainerHighest = md_theme_light_surfaceContainerHighest,
    )

/**
 * Dark color scheme with ListenUp coral as seed color.
 */
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
        surfaceContainerLowest = md_theme_dark_surfaceContainerLowest,
        surfaceContainerLow = md_theme_dark_surfaceContainerLow,
        surfaceContainer = md_theme_dark_surfaceContainer,
        surfaceContainerHigh = md_theme_dark_surfaceContainerHigh,
        surfaceContainerHighest = md_theme_dark_surfaceContainerHighest,
    )
