package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Persisted-status wire shape. Shared between [com.calypsan.listenup.server.auth.AuthServiceImpl.observeRegistrationStatus]
 * (the RPC watch) and, until it's retired, the legacy `RegistrationStatusRoutes` SSE route —
 * neither should read the `users` table independently.
 */
internal const val STATUS_PENDING = "pending"

/** Fallback re-check cadence for the persisted status — see [pollUntilTerminal]. */
private const val STATUS_RECHECK_INTERVAL_MILLIS = 3_000L

/**
 * Reads the registrant's persisted status. `null` means [userId] does not exist at all — distinct
 * from a real row sitting in `PENDING_APPROVAL`. Callers decide how to surface that: the RPC watch
 * fails typed ([com.calypsan.listenup.api.error.AuthError.RegistrationNotFound]); the legacy SSE
 * route (which predates that distinction) falls back to `"pending"`, preserving its original
 * behaviour verbatim.
 */
internal suspend fun readRegistrationStatus(
    db: ListenUpDatabase,
    userId: String,
): RegistrationStatusEvent? =
    suspendTransaction(db) {
        db.usersQueries.selectById(userId).executeAsOneOrNull()
    }?.status?.let { status ->
        when (status) {
            "ACTIVE" -> RegistrationStatusEvent(status = "approved")
            "DENIED" -> RegistrationStatusEvent(status = "denied")
            else -> RegistrationStatusEvent(status = STATUS_PENDING)
        }
    }

/**
 * Re-reads the persisted status on a fixed cadence, emitting once it leaves [STATUS_PENDING].
 *
 * The "never stranded" safety net for a decision the live [RegistrationBroadcaster] push missed
 * (the broadcaster is `replay = 0` — a decision notified while nobody is subscribed is dropped).
 * Shared by the RPC watch and (until it's retired) the legacy SSE route.
 */
internal fun pollUntilTerminal(
    userId: String,
    currentStatus: suspend (userId: String) -> RegistrationStatusEvent,
): Flow<RegistrationStatusEvent> =
    flow {
        while (true) {
            delay(STATUS_RECHECK_INTERVAL_MILLIS)
            val status = currentStatus(userId)
            if (status.status != STATUS_PENDING) {
                emit(status)
                break
            }
        }
    }

/** Maps a live broadcaster decision onto the same wire shape [readRegistrationStatus] produces. */
internal fun RegistrationDecision.toEvent(): RegistrationStatusEvent =
    when (this) {
        RegistrationDecision.Approved -> RegistrationStatusEvent(status = "approved")
        is RegistrationDecision.Denied -> RegistrationStatusEvent(status = "denied", message = message)
    }
