package com.calypsan.listenup.client.device

/**
 * Form factor classification used by [DeviceContext] to pick layouts and capabilities.
 * Detected once per process by the platform-specific [DeviceContextProvider].
 */
enum class DeviceType {
    Phone,
    Tablet,
    Watch,
    Tv,
    Desktop,
    Xr,
    Auto,
}
