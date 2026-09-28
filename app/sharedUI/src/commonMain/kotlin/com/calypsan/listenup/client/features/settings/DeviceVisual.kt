package com.calypsan.listenup.client.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.TabletMac
import androidx.compose.ui.graphics.vector.ImageVector
import com.calypsan.listenup.client.design.theme.CategoryColor
import com.calypsan.listenup.client.design.theme.CategoryPalette

/**
 * Icon and category colour resolved from a raw session `deviceType` string.
 *
 * @param icon The outlined Material icon representing the device category.
 * @param color The device category's colour; resolve its tone for the theme with [CategoryColor.current].
 */
data class DeviceVisual(
    val icon: ImageVector,
    val color: CategoryColor,
)

/**
 * Maps a raw session `deviceType` string to a [DeviceVisual] (icon + category colour). Input is
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
        "phone" -> DeviceVisual(Icons.Outlined.Smartphone, CategoryPalette.Blue)
        "tablet" -> DeviceVisual(Icons.Outlined.TabletMac, CategoryPalette.Violet)
        "desktop", "laptop" -> DeviceVisual(Icons.Outlined.Computer, CategoryPalette.Sky)
        "cast" -> DeviceVisual(Icons.Outlined.Cast, CategoryPalette.Green)
        "speaker" -> DeviceVisual(Icons.Outlined.Speaker, CategoryPalette.Orange)
        else -> DeviceVisual(Icons.Outlined.Devices, CategoryPalette.Neutral)
    }
