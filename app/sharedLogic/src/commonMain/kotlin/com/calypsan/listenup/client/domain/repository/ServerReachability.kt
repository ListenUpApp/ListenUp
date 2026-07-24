package com.calypsan.listenup.client.domain.repository

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
     * Force a fresh reachability check by tearing down and re-establishing the sync
     * firehose. Backs the "Retry" action on the offline banner — a manual, never-stranded
     * fallback for when the automatic reconnect backoff hasn't yet recovered.
     */
    suspend fun retry()
}
