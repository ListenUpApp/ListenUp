package com.calypsan.listenup.client.design

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The smallest touch target any control gets: Material's 48dp minimum. */
val MinTouchTarget = 48.dp

/**
 * Keeps a compact control's [footprint] in the layout while giving it a [MinTouchTarget] body centred
 * on that footprint — the extra reaches into the padding around it, so a dense header or chip gains a
 * full target without growing.
 *
 * Chain it first on the control's modifier. Anything drawn by the control itself fills the 48dp body,
 * so draw a smaller visual (a tinted circle, say) on the control's content instead.
 */
fun Modifier.compactTouchTarget(footprint: Dp): Modifier =
    this
        .size(footprint)
        .wrapContentSize(unbounded = true)
        .size(MinTouchTarget)
