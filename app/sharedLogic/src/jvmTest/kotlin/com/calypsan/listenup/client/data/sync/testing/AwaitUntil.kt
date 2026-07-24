package com.calypsan.listenup.client.data.sync.testing

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Suspends until [predicate] is true, probing every [probeInterval] instead of
 * hot-spinning, failing with a timeout after [timeout]. The canonical wait
 * primitive for E2E round-trip assertions — replaces per-file
 * `while (cond) { }` busy loops that hammer Room/SQLite under load.
 */
suspend fun awaitUntil(
    timeout: Duration = 30.seconds,
    probeInterval: Duration = 25.milliseconds,
    predicate: suspend () -> Boolean,
) {
    withTimeout(timeout) {
        while (!predicate()) {
            delay(probeInterval)
        }
    }
}
