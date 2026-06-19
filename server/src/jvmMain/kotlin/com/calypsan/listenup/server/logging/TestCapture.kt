package com.calypsan.listenup.server.logging

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe accumulator of [CapturedEvent] values, installed by tests to intercept
 * log output without touching stdout.
 */
class TestCapture {
    private val _events = CopyOnWriteArrayList<CapturedEvent>()

    /** All events captured so far. Safe to read from any thread. */
    val events: List<CapturedEvent>
        get() = _events

    internal fun add(event: CapturedEvent) {
        _events.add(event)
    }
}
