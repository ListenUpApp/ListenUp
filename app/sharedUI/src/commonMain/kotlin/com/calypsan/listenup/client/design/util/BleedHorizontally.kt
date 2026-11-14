package com.calypsan.listenup.client.design.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset

/**
 * Widens this element by [bleed] on each side and centres it, so it reaches past a parent's
 * horizontal padding. `Modifier.padding` cannot do this: it rejects a negative value.
 */
internal fun Modifier.bleedHorizontally(bleed: Dp): Modifier =
    layout { measurable, constraints ->
        val bleedPx = bleed.roundToPx()
        val placeable = measurable.measure(constraints.offset(horizontal = bleedPx * 2))
        layout(constraints.constrainWidth(placeable.width - bleedPx * 2), placeable.height) {
            placeable.place(-bleedPx, 0)
        }
    }
