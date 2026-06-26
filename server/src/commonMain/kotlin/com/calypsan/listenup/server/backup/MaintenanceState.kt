package com.calypsan.listenup.server.backup

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay

/**
 * Tracks whether a restore is in progress (the "gate") and the count of in-flight
 * non-gated requests (for draining before the DB swap).
 *
 * The gate is single-flight: [enter] uses a compare-and-set so only one restore can
 * run at a time. [drain] polls until all requests that were admitted before the gate
 * was raised have completed, or until [timeoutMs] elapses.
 *
 * Thread-safe: all state is held in atomicfu atomics; the public API is safe to call
 * from any coroutine dispatcher.
 */
class MaintenanceState {
    private val active = atomic(false)
    private val inFlight = atomic(0)

    /** `true` when a restore is in progress and the gate is raised. */
    val isActive: Boolean get() = active.value

    /**
     * Raises the maintenance gate, preventing new requests from reaching route handlers.
     *
     * @return `true` if the gate was successfully raised; `false` if a restore is already
     *   running (single-flight guarantee).
     */
    fun enter(): Boolean = active.compareAndSet(expect = false, update = true)

    /** Lowers the maintenance gate, allowing normal request processing to resume. */
    fun exit() {
        active.value = false
    }

    /** Records that a request has been admitted past the gate (called before the route handler). */
    fun beginRequest(): Int = inFlight.incrementAndGet()

    /** Records that an admitted request has completed (called after the route handler). */
    fun endRequest(): Int = inFlight.decrementAndGet()

    /** Returns the current count of in-flight non-allowlisted requests. Intended for testing. */
    fun inFlightCount(): Int = inFlight.value

    /**
     * Waits until all in-flight requests drain to zero or [timeoutMs] elapses.
     *
     * @return `true` if all requests drained within the timeout; `false` if the timeout
     *   was reached with in-flight requests still running.
     */
    suspend fun drain(
        timeoutMs: Long = 10_000,
        stepMs: Long = 50,
    ): Boolean {
        var waited = 0L
        while (inFlight.value > 0 && waited < timeoutMs) {
            delay(stepMs)
            waited += stepMs
        }
        return inFlight.value == 0
    }
}
