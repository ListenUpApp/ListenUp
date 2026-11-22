package com.calypsan.listenup.client.design.theme

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * Whether this device can show Material You dynamic colour: Android 12 or newer, and not a TV,
 * which has no wallpaper to derive colours from. The theme applies a dynamic scheme only when this
 * holds, and Settings offers the switch only when it holds, so the two cannot disagree.
 */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
internal fun Context.supportsDynamicColor(): Boolean = supportsDynamicColor(Build.VERSION.SDK_INT, isTelevision(this))

/** The decision behind [Context.supportsDynamicColor], on plain facts. */
internal fun supportsDynamicColor(
    sdkInt: Int,
    isTelevision: Boolean,
): Boolean = sdkInt >= Build.VERSION_CODES.S && !isTelevision

private fun isTelevision(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}
