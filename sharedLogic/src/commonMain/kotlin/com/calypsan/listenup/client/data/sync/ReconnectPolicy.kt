package com.calypsan.listenup.client.data.sync

import kotlin.math.pow

internal const val INITIAL_RECONNECT_DELAY_MS = 1_000L
internal const val MAX_RECONNECT_DELAY_MS = 60_000L
internal const val RECONNECT_BACKOFF_MULTIPLIER = 2.0

/**
 * Upper bound on **silence** during a streaming read — the half-open-connection watchdog.
 *
 * A NAT rebind, AP roam, router restart or buffering proxy can kill the TCP path with no RST: the
 * socket is dead and nothing says so, and an unbounded read blocks forever. The connection state
 * then stays `Connected` — a lie that stands the whole recovery stack down, because
 * `ReconnectionSupervisor` is gated on not-connected, the outbox drains on the connection-up edge,
 * and the supervisor's HTTP probe *succeeds* against a half-open stream and so masks the offline
 * banner. The app looks online and receives nothing, indefinitely.
 *
 * The server already emits the signal to detect it: the RPC firehose heartbeats every 25s
 * (`SyncStreamServiceImpl`'s `HEARTBEAT_INTERVAL_MILLIS`). This bound is 3× that interval, so a
 * live-but-idle stream is never torn down (two heartbeats may be lost before we act) while a dead
 * one is caught quickly. Any received frame — heartbeat or data — resets the window. Consumed by
 * the RPC firehose watchdog ([RpcSyncStreamClient]).
 */
internal const val DEFAULT_READ_IDLE_TIMEOUT_MS = 75_000L

/** 1s, 2s, 4s, 8s, 16s, 32s, 60s, 60s, 60s, ... — caps at [MAX_RECONNECT_DELAY_MS]. */
internal fun reconnectDelayMillis(attempt: Int): Long {
    val raw =
        (
            INITIAL_RECONNECT_DELAY_MS.toDouble() *
                RECONNECT_BACKOFF_MULTIPLIER.pow(attempt.toDouble())
        ).toLong()
    return raw.coerceAtMost(MAX_RECONNECT_DELAY_MS)
}
