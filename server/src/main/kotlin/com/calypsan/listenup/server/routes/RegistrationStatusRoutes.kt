package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.server.auth.RegistrationBroadcaster
import com.calypsan.listenup.server.auth.RegistrationDecision
import io.ktor.server.routing.Route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Comment-line keepalive cadence, mirroring the sync firehose's 25s default: keeps the
// idle pre-approval connection alive across NATs/load balancers that drop silent streams.
private const val HEARTBEAT_INTERVAL_MILLIS = 25_000L

/**
 * Mounts the unauthenticated registration-status SSE stream:
 *  - `GET /api/v1/auth/registration-status/{userId}/stream`
 *
 * A registrant awaiting approval has no JWT yet, so this route lives OUTSIDE the auth wall.
 * On connect it emits a `RegistrationStatusEvent(status = "pending")`, then suspends until the
 * admin's decision arrives via [broadcaster], emits the terminal `"approved"`/`"denied"` event,
 * and closes. A comment-line keepalive runs in the background to hold the idle connection open.
 *
 * The pending event is sent from inside [onSubscription], which fires only after this collector
 * is registered as a live subscriber but before any upstream decision is processed.
 * [RegistrationBroadcaster] is `replay = 0`, so any other ordering — subscribe, then send pending,
 * then start collecting — would drop a decision fired in that window. [onSubscription] closes the
 * window entirely; the client's reconnect/retry-login path is the fallback for a true disconnect.
 */
fun Route.registrationStatusRoutes(broadcaster: RegistrationBroadcaster) {
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
            // first() suspends until the first approve/deny decision; onSubscription emits the
            // pending event the instant this collector is live, so no decision can slip the gap.
            // When first() returns the block ends and the SSE session closes — matching the
            // client, which closes on a terminal status.
            val decision =
                broadcaster
                    .subscribe(userId)
                    .onSubscription { sendStatus(RegistrationStatusEvent(status = "pending")) }
                    .first()
            sendStatus(decision.toEvent())
        } finally {
            heartbeat.cancel()
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
