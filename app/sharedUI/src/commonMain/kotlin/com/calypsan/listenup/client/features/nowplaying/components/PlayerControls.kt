package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics

/**
 * Whether tapping a [PlayerIconButton] fires [com.calypsan.listenup.client.design.haptics.Haptics.press]. Transport
 * actions press; chrome that only navigates passes [None] — you should feel a transport press, not a trip to
 * another screen.
 */
enum class PressFeedback {
    /** The tap does something to playback (skip, chapter step): it presses. */
    Press,

    /** The tap only navigates (the docked bar's expand control): it stays silent. */
    None,
}

/**
 * Round transparent icon-button.
 *
 * @param icon The icon vector to display.
 * @param contentDescription Accessibility label.
 * @param onClick Click callback.
 * @param size Touch-target diameter.
 * @param iconSize The glyph's size — the control's visual size, so a compact bar can keep a small
 * glyph on a full 48dp target. Half of [size] by default.
 * @param tint Icon colour.
 * @param feedback Whether tapping presses. Defaults to [PressFeedback.Press] because every transport use of this
 * button *does* something (skip, chapter step).
 */
@Composable
fun PlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp = 60.dp,
    iconSize: Dp = size * 0.5f,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    feedback: PressFeedback = PressFeedback.Press,
) {
    val haptics = LocalHaptics.current
    IconButton(
        onClick = {
            if (feedback == PressFeedback.Press) haptics.press()
            onClick()
        },
        modifier = Modifier.size(size),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = tint,
        )
    }
}
