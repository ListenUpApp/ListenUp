package com.calypsan.listenup.client.device

actual class DeviceContextProvider {
    actual fun detect(): DeviceContext = DeviceContext(DeviceType.Desktop)
}
