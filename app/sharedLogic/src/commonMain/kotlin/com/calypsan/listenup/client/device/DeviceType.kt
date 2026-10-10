package com.calypsan.listenup.client.device

/**
 * Form factor classification used by [DeviceContext] to pick layouts and capabilities.
 * Detected once per process by the platform-specific [DeviceContextProvider].
 *
 * @property wireName What a session of this form factor reports as `DeviceInfo.deviceType`. The
 *   server stores it as given and validates nothing, so changing one reclassifies existing sessions.
 */
enum class DeviceType(
    val wireName: String,
) {
    Phone("phone"),
    Tablet("tablet"),
    Watch("watch"),
    Tv("tv"),
    Desktop("desktop"),
    Xr("xr"),
    Auto("auto"),
}
