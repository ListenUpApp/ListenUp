package com.calypsan.listenup.client.device

/**
 * The `DeviceInfo.deviceType` string this form factor is reported as.
 *
 * There is no enum on the wire — the server stores `device_type` opaquely — so the vocabulary
 * is whatever the client's own `deviceVisualFor` understands. `phone`, `tablet` and `desktop`
 * get a specific glyph in the Devices screen; the rest fall through to its generic device icon,
 * which is honest (a car is not a phone) and better than the hard-coded "phone" this replaces.
 *
 * Lives in `androidMain` of `:app:sharedUI` rather than shared code: it is Android's answer to
 * the question, and keeping it here keeps it off the iOS export surface.
 */
internal fun DeviceType.wireName(): String =
    when (this) {
        DeviceType.Phone -> "phone"
        DeviceType.Tablet -> "tablet"
        DeviceType.Desktop -> "desktop"
        DeviceType.Tv -> "tv"
        DeviceType.Auto -> "auto"
        DeviceType.Watch -> "watch"
        DeviceType.Xr -> "xr"
    }
