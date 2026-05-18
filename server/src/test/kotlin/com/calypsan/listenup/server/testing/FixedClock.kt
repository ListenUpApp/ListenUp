@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.testing

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** A fixed-point [kotlin.time.Clock] for deterministic time in tests. */
class FixedClock(
    private val fixed: Instant,
) : Clock {
    override fun now(): Instant = fixed
}
