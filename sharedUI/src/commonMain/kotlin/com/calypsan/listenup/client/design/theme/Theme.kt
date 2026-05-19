package com.calypsan.listenup.client.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive shape system with larger corner radii.
 * Creates a softer, more expressive visual language compared to standard M3.
 *
 * - extraSmall/small: Input fields, chips
 * - medium (20.dp): Buttons, text fields - primary touch targets
 * - large (28.dp): Cards, elevated surfaces
 * - extraLarge: Fully rounded elements (FABs, avatars)
 */
private val ExpressiveShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = CircleShape,
    )

/**
 * Composition local for accessing the current theme's dark mode state.
 * Use this instead of isSystemInDarkTheme() to respect the app's actual theme,
 * not just the system setting.
 *
 * Usage: val isDark = LocalDarkTheme.current
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * Platform-specific color scheme selection.
 *
 * On Android 12+: Uses dynamic color from system wallpaper when dynamicColor is true.
 * On Desktop: Always uses the static ListenUp color scheme (ignores dynamicColor).
 */
@Composable
expect fun platformColorScheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
): ColorScheme

/**
 * ListenUp Material 3 theme.
 *
 * Features:
 * - Dynamic color on Android 12+ (adapts to user's wallpaper)
 * - Respects system dark/light mode setting
 * - Fallback to ListenUpOrange seed color on older devices and desktop
 * - Expressive shapes with larger corner radii (20-28dp)
 *
 * @param darkTheme Whether to use dark theme. Defaults to system setting.
 * @param dynamicColor Whether to use dynamic color from system (Android 12+).
 *                     Defaults to true. Ignored on desktop.
 * @param content The composable content to theme.
 */
@Composable
fun ListenUpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = platformColorScheme(darkTheme, dynamicColor)

    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ListenUpTypography,
            shapes = ExpressiveShapes,
            content = content,
        )
    }
}
