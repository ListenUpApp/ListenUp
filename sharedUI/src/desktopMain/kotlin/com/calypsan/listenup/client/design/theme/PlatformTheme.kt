package com.calypsan.listenup.client.design.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/**
 * Desktop-specific color scheme selection.
 * Desktop doesn't support dynamic color, so always uses static ListenUp theme.
 */
@Composable
actual fun platformColorScheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
): ColorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
