package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.server.auth.RegistrationPolicyBroadcaster
import io.ktor.server.routing.Route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Comment-line keepalive cadence, mirroring the registration-status stream: holds the idle
// login-screen connection open across NATs/proxies that drop silent streams.
private const val HEARTBEAT_INTERVAL_MILLIS = 25_000L

// Fallback re-read cadence for the persisted policy. The live broadcast delivers a change instantly
// to subscribed clients; this poll is the "never stranded" net that advances the stream even if a
// live push was missed (the broadcaster is replay = 0).
private const val POLICY_RECHECK_INTERVAL_MILLIS = 30_000L

/**
 * Mounts the unauthenticated live registration-policy stream:
 *  - `GET /api/v1/auth/registration-policy/stream` — SSE push of the instance-wide [RegistrationPolicy].
 *
 * A client on the login screen has no JWT, so this route lives OUTSIDE the auth wall. On connect it
 * emits the current persisted policy immediately ([onStart]), then every change — from an instant
 * live broadcast ([broadcaster]) or a periodic persisted re-read (the never-stranded fallback) —
 * de-duplicated so an unchanged re-read is silent. The stream stays open (a policy can change any
 * number of times); a comment-line keepalive holds the idle connection across proxies.
 *
 * The client maps `policy != CLOSED` to "registration open", flipping the Sign Up affordance live.
 * Server-side enforcement is unchanged and authoritative — this stream only keeps the UI honest.
 *
 * @param currentPolicy reads the current persisted instance-wide policy.
 */
fun Route.registrationPolicyRoutes(
    broadcaster: RegistrationPolicyBroadcaster,
    currentPolicy: suspend () -> RegistrationPolicy,
) {
    sse("/api/v1/auth/registration-policy/stream") {
        val heartbeat =
            launch {
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL_MILLIS)
                    send(ServerSentEvent(comments = "keepalive"))
                }
            }
        try {
            // Emit the current policy the instant the broadcaster collector registers (onSubscription),
            // then every live change. Placing the initial emit ON the broadcaster subscription — not on
            // the merged flow — closes the connect→notify window: a change pushed right after connect
            // can't slip through before we're subscribed (the broadcaster is replay = 0). The periodic
            // re-read is the never-stranded net; distinctUntilChanged keeps an unchanged re-read silent.
            merge(
                broadcaster.subscribe().onSubscription { emit(currentPolicy()) },
                pollPolicy(currentPolicy),
            ).distinctUntilChanged()
                .collect { sendPolicy(it) }
        } finally {
            heartbeat.cancel()
        }
    }
}

/** Re-reads the persisted policy on a fixed cadence — the never-stranded net for a missed broadcast. */
private fun pollPolicy(currentPolicy: suspend () -> RegistrationPolicy): Flow<RegistrationPolicy> =
    flow {
        while (true) {
            delay(POLICY_RECHECK_INTERVAL_MILLIS)
            emit(currentPolicy())
        }
    }

/** Emits a [RegistrationPolicy] as a data-only SSE frame; the client decodes `data:` regardless of `event:`. */
private suspend fun ServerSSESession.sendPolicy(policy: RegistrationPolicy) {
    send(data = contractJson.encodeToString(RegistrationPolicy.serializer(), policy))
}
