package com.calypsan.listenup.client.device

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

actual class DeviceContextProvider(
    private val context: Context,
) {
    actual fun detect(): DeviceContext {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        val type =
            when (uiModeManager.currentModeType) {
                Configuration.UI_MODE_TYPE_TELEVISION -> {
                    DeviceType.Tv
                }

                Configuration.UI_MODE_TYPE_CAR -> {
                    DeviceType.Auto
                }

                Configuration.UI_MODE_TYPE_WATCH -> {
                    DeviceType.Watch
                }

                else -> {
                    val smallestWidth = context.resources.configuration.smallestScreenWidthDp
                    if (smallestWidth >= 600) DeviceType.Tablet else DeviceType.Phone
                }
            }
        return DeviceContext(type)
    }
}
