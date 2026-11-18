package com.calypsan.listenup.client.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Whether the active server is currently reachable, for gating streaming/download UI. */
sealed interface Reachability {
    /** Server connection is live. */
    data object Reachable : Reachability

    /** Server connection is down. */
    data object Unreachable : Reachability

    /** Not yet determined (e.g. connecting at startup) — treat optimistically. */
    data object Unknown : Reachability
}

/** Reactive server reachability, derived from the live sync firehose connection. */
interface ServerReachability {
    val state: StateFlow<Reachability>

    /**
     * Emits each time the live server connection comes back after dropping, including a reconnect so quick that
     * [state] never left [Reachability.Reachable]. A surface holding a failed one-off fetch retries on it: waiting
     * for [state] to flip leaves that fetch failed until a manual refresh whenever the outage was shorter than the
     * health debounce.
     */
    val regained: Flow<Unit>

    /**
     * Force a fresh reachability check by tearing down and re-establishing the sync
     * firehose. Backs the "Retry" action on Book Detail's offline reason — a manual, never-stranded
     * fallback for when the automatic reconnect backoff hasn't yet recovered.
     */
    suspend fun retry()
}
