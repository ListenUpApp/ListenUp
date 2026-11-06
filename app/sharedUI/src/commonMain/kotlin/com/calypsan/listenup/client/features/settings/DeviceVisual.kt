package com.calypsan.listenup.client.features.settings

import androidx.compose.ui.graphics.vector.ImageVector
import com.calypsan.listenup.client.design.icons.ListenUpIcons
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
        DeviceGlyph.Phone -> DeviceVisual(ListenUpIcons.Smartphone, CategoryPalette.Blue)
        DeviceGlyph.Tablet -> DeviceVisual(ListenUpIcons.TabletMac, CategoryPalette.Violet)
        DeviceGlyph.Computer -> DeviceVisual(ListenUpIcons.Computer, CategoryPalette.Sky)
        DeviceGlyph.Browser -> DeviceVisual(ListenUpIcons.Language, CategoryPalette.Teal)
        DeviceGlyph.Tv -> DeviceVisual(ListenUpIcons.Tv, CategoryPalette.Periwinkle)
        DeviceGlyph.Watch -> DeviceVisual(ListenUpIcons.Watch, CategoryPalette.Magenta)
        DeviceGlyph.Car -> DeviceVisual(ListenUpIcons.DirectionsCar, CategoryPalette.Orange)
        DeviceGlyph.Headset -> DeviceVisual(ListenUpIcons.Headset, CategoryPalette.Periwinkle)
        DeviceGlyph.Cast -> DeviceVisual(ListenUpIcons.Cast, CategoryPalette.Green)
        DeviceGlyph.Speaker -> DeviceVisual(ListenUpIcons.Speaker, CategoryPalette.Orange)
        DeviceGlyph.Unknown -> DeviceVisual(ListenUpIcons.Devices, CategoryPalette.Neutral)
    }
