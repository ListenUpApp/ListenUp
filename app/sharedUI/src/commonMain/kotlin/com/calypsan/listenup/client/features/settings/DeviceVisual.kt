package com.calypsan.listenup.client.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.TabletMac
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

private val COLOR_PHONE = Color(0xFF2A6FDB)
private val COLOR_TABLET = Color(0xFF7A5AF8)
private val COLOR_DESKTOP = Color(0xFF3A86C2)
private val COLOR_CAST = Color(0xFF1F8A5B)
private val COLOR_SPEAKER = Color(0xFFC2562A)
private val COLOR_UNKNOWN = Color(0xFF8A8A8E)

/**
 * Icon and tint colour resolved from a raw session `deviceType` string.
 *
 * @param icon The outlined Material icon representing the device category.
 * @param tint The accent colour to apply to [icon].
 */
data class DeviceVisual(
    val icon: ImageVector,
    val tint: Color,
)

/**
 * Maps a raw session `deviceType` string to a [DeviceVisual] (icon + tint). Input is
 * lowercased before matching so server casing is irrelevant.
 *
 * In practice today only `"phone"`, `"desktop"`, and `null` occur in the wild — the remaining
 * variants ("tablet", "cast", "speaker") are included for forward-compatibility as the server
 * expands its device-type vocabulary.
 *
 * @param deviceType The raw device-type string from the session, or `null` for unknown/legacy.
 */
fun deviceVisualFor(deviceType: String?): DeviceVisual =
    when (deviceType?.lowercase()) {
        "phone" -> DeviceVisual(Icons.Outlined.Smartphone, COLOR_PHONE)
        "tablet" -> DeviceVisual(Icons.Outlined.TabletMac, COLOR_TABLET)
        "desktop", "laptop" -> DeviceVisual(Icons.Outlined.Computer, COLOR_DESKTOP)
        "cast" -> DeviceVisual(Icons.Outlined.Cast, COLOR_CAST)
        "speaker" -> DeviceVisual(Icons.Outlined.Speaker, COLOR_SPEAKER)
        else -> DeviceVisual(Icons.Outlined.Devices, COLOR_UNKNOWN)
    }
