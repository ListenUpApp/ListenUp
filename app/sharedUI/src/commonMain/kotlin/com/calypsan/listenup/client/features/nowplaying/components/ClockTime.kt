package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.runtime.Composable

/**
 * The device's own short clock time for a wall-clock instant ("11:42 PM", or "23:42" on a 24-hour phone), for the
 * sleep sheet's "Pauses at" (C16): a timer is about the clock on the nightstand, so it must read the way the phone's
 * own clock does — its 12/24-hour setting and its locale.
 *
 * @return A formatter from epoch milliseconds to the short local time, remembered for the composition.
 */
@Composable
expect fun rememberClockTimeFormatter(): (epochMs: Long) -> String
