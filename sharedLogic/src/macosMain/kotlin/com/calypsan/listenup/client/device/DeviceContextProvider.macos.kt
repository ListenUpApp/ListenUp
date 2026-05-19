package com.calypsan.listenup.client.device

/**
 * macOS implementation of DeviceContextProvider.
 * Always returns Desktop type.
 */
actual class DeviceContextProvider {
    actual fun detect(): DeviceContext = DeviceContext(DeviceType.Desktop)
}
