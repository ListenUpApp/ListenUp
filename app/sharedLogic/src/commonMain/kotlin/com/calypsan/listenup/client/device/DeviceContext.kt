package com.calypsan.listenup.client.device

/**
 * Capability snapshot for the current device. Wraps a [DeviceType] with derived capability
 * predicates (touch, D-pad, whether the form factor supports editing, etc.) — form-factor
 * capabilities, not the user's permissions (see `PermissionsRepository`). The UI consults them to
 * choose layouts and affordances appropriate to the form factor.
 */
data class DeviceContext(
    val type: DeviceType,
) {
    val hasTouch: Boolean get() = type in setOf(DeviceType.Phone, DeviceType.Tablet, DeviceType.Xr, DeviceType.Watch)
    val hasDpad: Boolean get() = type in setOf(DeviceType.Tv, DeviceType.Auto)
    val supportsEditing: Boolean get() = type in setOf(DeviceType.Phone, DeviceType.Tablet, DeviceType.Desktop)
    val isLeanback: Boolean get() = type == DeviceType.Tv
    val prefersLargeTargets: Boolean get() = type in setOf(DeviceType.Tv, DeviceType.Xr, DeviceType.Auto)
    val isWearable: Boolean get() = type == DeviceType.Watch
    val supportsFullLibrary: Boolean get() =
        type in
            setOf(DeviceType.Phone, DeviceType.Tablet, DeviceType.Desktop, DeviceType.Tv)
    val supportsDownloads: Boolean get() =
        type in
            setOf(DeviceType.Phone, DeviceType.Tablet, DeviceType.Watch)
}
