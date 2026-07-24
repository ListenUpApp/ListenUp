package com.calypsan.listenup.client.design

import androidx.compose.runtime.staticCompositionLocalOf
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceType

val LocalDeviceContext = staticCompositionLocalOf { DeviceContext(DeviceType.Phone) }
