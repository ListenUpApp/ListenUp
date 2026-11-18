package com.calypsan.listenup.client.design.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** How often [rememberMinuteClockMs] re-reads the clock: twice a minute, so a minute boundary is never more than half a minute late. */
private const val CLOCK_STEP_MS = 30_000L

/** Now in epoch milliseconds, re-read every half minute so "2 min ago" keeps up without a tick per frame. */
@Composable
internal fun rememberMinuteClockMs(): State<Long> =
    produceState(initialValue = Clock.System.now().toEpochMilliseconds()) {
        while (true) {
            delay(CLOCK_STEP_MS)
            value = Clock.System.now().toEpochMilliseconds()
        }
    }
