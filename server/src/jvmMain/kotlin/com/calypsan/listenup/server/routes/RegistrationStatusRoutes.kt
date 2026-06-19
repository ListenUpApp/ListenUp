package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.server.auth.RegistrationBroadcaster
import com.calypsan.listenup.server.auth.RegistrationDecision
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Comment-line keepalive cadence, mirroring the sync firehose's 25s default: keeps the
// idle pre-approval connection alive across NATs/load balancers that drop silent streams.
private const val HEARTBEAT_INTERVAL_MILLIS = 25_000L

// Fallback re-check cadence for the persisted status. The live broadcast delivers a decision
// instantly when the registrant is subscribed at that instant; this poll is the "never stranded"
// safety net that advances the stream even if that live push was missed (replay=0 broadcaster).
private const val STATUS_RECHECK_INTERVAL_MILLIS = 3_000L

private const val STATUS_PENDING = "pending"

/**
 * Mounts the unauthenticated registration-status surface:
 *  - `GET /api/v1/auth/registration-status/{userId}` — one-shot status (the pull fallback)
 *  - `GET /api/v1/auth/registration-status/{userId}/stream` — SSE push stream
 *
 * A registrant awaiting approval has no JWT yet, so these routes live OUTSIDE the auth wall.
 *
 * On connect it emits the registrant's **persisted** status via [currentStatus]: a registrant who
 * connects (or reconnects, or taps "check status") *after* the admin's decision learns it
 * immediately rather than waiting forever on `"pending"`. This is the fix for the iOS Awaiting
 * screen never advancing — [RegistrationBroadcaster] is `replay = 0`, so a decision pushed while
 * the registrant wasn't subscribed is otherwise lost.
 *
 * While still pending it awaits a terminal decision from **either** an instant live broadcast
 * ([broadcaster]) **or** a periodic re-check of the persisted status — whichever fires first —
 * then emits the terminal `"approved"`/`"denied"` event and closes. A comment-line keepalive
 * holds the idle connection open across proxies.
 *
 * @param currentStatus reads the registrant's persisted status as a wire event; an unknown user
 *   id maps to `"pending"` (the stream simply waits).
 */
fun Route.registrationStatusRoutes(
    broadcaster: RegistrationBroadcaster,
    currentStatus: suspend (userId: String) -> RegistrationStatusEvent,
) {
    // Plain request/response status check — the "never stranded" pull fallback for clients where
    // the SSE stream doesn't deliver (e.g. the iOS Darwin engine). "Check Status" / a poll / an
    // on-launch check hit this and learn the persisted decision with no streaming or WebSocket.
    get("/api/v1/auth/registration-status/{userId}") {
        val userId = call.parameters["userId"]
        if (userId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest)
        } else {
            call.respond(currentStatus(userId))
        }
    }

    sse("/api/v1/auth/registration-status/{userId}/stream") {
        val userId = call.parameters["userId"] ?: return@sse

        val heartbeat =
            launch {
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL_MILLIS)
                    send(ServerSentEvent(comments = "keepalive"))
                }
            }
        try {
            val initial = currentStatus(userId)
            sendStatus(initial)
            if (initial.status == STATUS_PENDING) {
                // Still pending: resolve a terminal status from whichever source fires first.
                val terminal =
                    merge(
                        broadcaster.subscribe(userId).map { it.toEvent() },
                        pollUntilTerminal(userId, currentStatus),
                    ).first()
                sendStatus(terminal)
            }
        } finally {
            heartbeat.cancel()
        }
    }
}

/** Re-reads the persisted status on a fixed cadence, emitting once it leaves `"pending"`. */
private fun pollUntilTerminal(
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

/** Emits a [RegistrationStatusEvent] as a data-only SSE frame; the client decodes `data:` regardless of `event:`. */
private suspend fun ServerSSESession.sendStatus(event: RegistrationStatusEvent) {
    send(data = contractJson.encodeToString(RegistrationStatusEvent.serializer(), event))
}

private fun RegistrationDecision.toEvent(): RegistrationStatusEvent =
    when (this) {
        RegistrationDecision.Approved -> RegistrationStatusEvent(status = "approved")
        is RegistrationDecision.Denied -> RegistrationStatusEvent(status = "denied", message = message)
    }
