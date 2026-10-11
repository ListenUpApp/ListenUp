package com.calypsan.listenup.server.scanner.pipeline

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Runs [block], calling [warn] once if it is still running after [after]. A read that never
 * returns holds the library's scan lock, and the path it is stuck on is the one fact an operator
 * needs.
 */
internal suspend fun <T> warningIfSlow(
    after: Duration,
    warn: () -> Unit,
    block: suspend () -> T,
): T =
    coroutineScope {
        val watchdog =
            launch {
                delay(after)
                warn()
            }
        try {
            block()
        } finally {
            watchdog.cancel()
        }
    }
