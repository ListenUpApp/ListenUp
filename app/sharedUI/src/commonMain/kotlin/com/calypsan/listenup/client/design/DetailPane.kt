package com.calypsan.listenup.client.design

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True while this content is the detail pane of a list-detail layout, drawn beside the list it was
 * opened from.
 *
 * There the screen's Back affordance no longer leaves anything: the list stays on screen either way.
 * What it does is close the pane, so a detail screen reading this shows a Close icon in place of the
 * back arrow. Its action is unchanged — popping the detail is exactly what closes the pane.
 */
val LocalInDetailPane: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }
