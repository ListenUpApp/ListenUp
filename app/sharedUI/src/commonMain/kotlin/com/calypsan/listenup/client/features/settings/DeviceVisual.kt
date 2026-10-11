package com.calypsan.listenup.client.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Headset
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.TabletMac
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.ui.graphics.vector.ImageVector
import com.calypsan.listenup.client.design.theme.CategoryColor
import com.calypsan.listenup.client.design.theme.CategoryPalette
import com.calypsan.listenup.client.device.DeviceGlyph

/**
 * Icon and category colour resolved from a session's [DeviceGlyph].
 *
 * @param icon The outlined Material icon representing the device category.
 * @param color The device category's colour; resolve its tone for the theme with [CategoryColor.current].
 */
data class DeviceVisual(
    val icon: ImageVector,
    val color: CategoryColor,
)

/**
 * Maps a [DeviceGlyph] to a [DeviceVisual] (icon + category colour). The `when` is exhaustive, so a glyph
 * added to the shared mapping cannot be forgotten here.
 *
 * @param glyph The kind of device the session came from, from [com.calypsan.listenup.client.device.deviceGlyphFor].
 */
fun deviceVisualFor(glyph: DeviceGlyph): DeviceVisual =
    when (glyph) {
        DeviceGlyph.Phone -> DeviceVisual(Icons.Outlined.Smartphone, CategoryPalette.Blue)
        DeviceGlyph.Tablet -> DeviceVisual(Icons.Outlined.TabletMac, CategoryPalette.Violet)
        DeviceGlyph.Computer -> DeviceVisual(Icons.Outlined.Computer, CategoryPalette.Sky)
        DeviceGlyph.Browser -> DeviceVisual(Icons.Outlined.Language, CategoryPalette.Teal)
        DeviceGlyph.Tv -> DeviceVisual(Icons.Outlined.Tv, CategoryPalette.Periwinkle)
        DeviceGlyph.Watch -> DeviceVisual(Icons.Outlined.Watch, CategoryPalette.Magenta)
        DeviceGlyph.Car -> DeviceVisual(Icons.Outlined.DirectionsCar, CategoryPalette.Orange)
        DeviceGlyph.Headset -> DeviceVisual(Icons.Outlined.Headset, CategoryPalette.Periwinkle)
        DeviceGlyph.Cast -> DeviceVisual(Icons.Outlined.Cast, CategoryPalette.Green)
        DeviceGlyph.Speaker -> DeviceVisual(Icons.Outlined.Speaker, CategoryPalette.Orange)
        DeviceGlyph.Unknown -> DeviceVisual(Icons.Outlined.Devices, CategoryPalette.Neutral)
    }
