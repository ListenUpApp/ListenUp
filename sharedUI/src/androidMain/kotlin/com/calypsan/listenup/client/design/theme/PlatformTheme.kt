package com.calypsan.listenup.client.design.theme

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
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
        // Dynamic color on Android 12+ - respects wallpaper and system theme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isTelevision(context) -> {
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }

        // Fallback for older devices or when dynamic color is disabled
        darkTheme -> {
            DarkColorScheme
        }

        else -> {
            LightColorScheme
        }
    }
}

/**
 * Check if the device is a TV. TVs do not properly support dynamic colors
 * even on API 31+ since there is no wallpaper to derive colors from.
 */
private fun isTelevision(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}
