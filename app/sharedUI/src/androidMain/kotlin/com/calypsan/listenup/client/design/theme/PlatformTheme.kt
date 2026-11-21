package com.calypsan.listenup.client.design.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Android-specific color scheme selection with Material You dynamic color support.
 */
@Composable
actual fun platformColorScheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
): ColorScheme {
    val context = LocalContext.current

    return when {
        // Android 11 and TVs fall through to the brand scheme; Settings hides the switch there too.
        dynamicColor && context.supportsDynamicColor() -> {
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }

        // Fallback when dynamic color is disabled
        darkTheme -> {
            DarkColorScheme
        }

        else -> {
            LightColorScheme
        }
    }
}
