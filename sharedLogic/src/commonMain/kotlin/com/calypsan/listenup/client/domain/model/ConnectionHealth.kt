package com.calypsan.listenup.client.domain.model

/**
 * App-level connection health derived from auth, reachability, and contract-compat signals.
 *
 * Client-local — it never crosses the wire, so it is deliberately NOT in `:contract` and NOT
 * `@Serializable`. The single source of truth is [com.calypsan.listenup.client.data.connection.ConnectionHealthStore];
 * UI and gating logic observe its derived state.
 */
internal sealed interface ConnectionHealth {
    /** Server reachable, session usable, contract parsing cleanly. Nothing is surfaced. */
    data object Healthy : ConnectionHealth

    /**
     * Server genuinely unreachable. The only "blocking" state — and it blocks only sync/streaming;
     * local content stays fully usable. Auto-retries; auto-heals on reconnect.
     *
     * @property sinceMillis epoch-millis the sustained-unreachable condition began (diagnostics only).
     */
    data class Unreachable(
        val sinceMillis: Long,
    ) : ConnectionHealth

    /**
     * Access token dead AND refresh failed. NOT a wall: local content usable, sync parked, a gentle
     * "Sign in to sync" affordance is shown. Auto-heals on successful re-auth. Mirrors
     * [AuthState.SessionLapsed] — one source of truth (the token store), two projections.
     */
    data object SessionExpired : ConnectionHealth

    /**
     * A response slice was unparseable/deprecated, or the peer version gap is meaningful. NOT a wall:
     * everything that parses keeps syncing; a non-blocking "Update available" hint is shown.
     *
     * @property clientVersion this build's version, for the directional hint copy.
     * @property serverVersion the peer server's version, for the directional hint copy.
     */
    data class Outdated(
        val clientVersion: String,
        val serverVersion: String,
    ) : ConnectionHealth
}
