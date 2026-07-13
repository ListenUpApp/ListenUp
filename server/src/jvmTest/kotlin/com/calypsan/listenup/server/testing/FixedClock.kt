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

/** An advanceable [kotlin.time.Clock] for exercising time-window behaviour (e.g. the refresh reuse-grace). */
class MutableClock(
    var instant: Instant,
) : Clock {
    override fun now(): Instant = instant
}
