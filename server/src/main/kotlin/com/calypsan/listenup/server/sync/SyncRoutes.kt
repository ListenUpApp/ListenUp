package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.DomainList
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import java.util.UUID
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject

private val log = KotlinLogging.logger("rpc.SyncFirehose")

// SSE `event:` line for out-of-band SyncControl frames (CursorStale, StreamError).
// Distinct from the per-domain `event: <domainName>` lines used for SyncEvent payloads.
private const val SSE_EVENT_CONTROL = "control"

/**
 * Mounts the Sync Foundation REST endpoints under `/api/v1/sync`:
 *  - `GET /api/v1/sync/events` — SSE firehose with `Last-Event-Id` resume
 *  - `GET /api/v1/sync/<domain>?since=<rev>&limit=<n>` — paginated catch-up
 *  - `GET /api/v1/sync/<domain>/digest?cursor=<rev>` — drift detection
 *  - `GET /api/v1/sync/domains` — registered domain list
 *
 * The firehose emits a comment-line keepalive every [heartbeatIntervalMillis]
 * milliseconds (25s by default) to prevent NAT/load-balancer drops on idle
 * connections. Tests pass a much shorter interval to keep the suite fast.
 */
fun Route.syncRoutes(heartbeatIntervalMillis: Long = 25_000L) {
    val bus by inject<ChangeBus>()
    val registry by inject<SyncRegistry>()

    // SSE firehose — streams every domain's BusEvents in real time.
    sse("/api/v1/sync/events") { streamFirehose(bus, heartbeatIntervalMillis) }

    get("/api/v1/sync/{domain}") {
        val userId =
            call.userPrincipalOrNull()?.userId?.value
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val domainName =
            call.parameters["domain"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "missing domain")
        val since =
            call.request.queryParameters["since"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "since must be a Long")
        val limit =
            call.request.queryParameters["limit"]
                ?.toIntOrNull()
                ?.coerceIn(1, 5000) ?: 500

        val repo =
            registry.lookup(domainName)
                ?: return@get call.respond(HttpStatusCode.NotFound, "unknown domain: $domainName")

        @Suppress("UNCHECKED_CAST")
        val typedRepo = repo as SyncableRepository<Any, Any>
        val page: Page<Any> = typedRepo.pullSince(userId, since, limit)
        // call.respond(page) would fail at runtime: kotlinx.serialization cannot
        // infer the concrete element serializer from the type-erased Page<Any>.
        // encodePageAsJson uses the concrete KSerializer<T> each repository provides.
        call.respondText(typedRepo.encodePageAsJson(page), ContentType.Application.Json)
    }

    get("/api/v1/sync/{domain}/digest") {
        val userId =
            call.userPrincipalOrNull()?.userId?.value
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val domainName =
            call.parameters["domain"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "missing domain")
        val cursor =
            call.request.queryParameters["cursor"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "cursor must be a Long")
        val repo =
            registry.lookup(domainName)
                ?: return@get call.respond(HttpStatusCode.NotFound, "unknown domain: $domainName")

        @Suppress("UNCHECKED_CAST")
        val typedRepo = repo as SyncableRepository<Any, Any>
        val digest: DomainDigest = typedRepo.digest(userId, cursor)
        call.respond(digest)
    }

    get("/api/v1/sync/domains") {
        call.respond(DomainList(domains = registry.knownDomains()))
    }
}

/**
 * Drives the SSE firehose for one connection: resolves the caller, applies
 * `Last-Event-Id` resume/cursor-stale handling, runs the keepalive side-job,
 * and streams [BusEvent]s the caller is entitled to see.
 *
 * The `event:` line carries the domain name; the `id:` line carries the
 * revision so a reconnecting client can resume via `Last-Event-Id`. A client
 * cursor older than the bus's replay-buffer floor — or a non-numeric one —
 * yields a [SyncControl.CursorStale] frame and closes, forcing REST catch-up.
 */
private suspend fun ServerSSESession.streamFirehose(
    bus: ChangeBus,
    heartbeatIntervalMillis: Long,
) {
    // The route group is mounted inside authenticate(JWT_PROVIDER), so a principal
    // is always present in production. The null guard is defence-in-depth — without
    // a user, per-user events cannot be safely filtered, so the stream is refused.
    val userId = call.userPrincipalOrNull()?.userId?.value ?: return

    // Malformed cursor (non-numeric) is treated identically to a stale cursor:
    // a corrupted Room cell or client-side bug must force REST catch-up rather
    // than silently subscribe to the live tail and diverge from server state.
    val lastEventId: Long? =
        call.request.headers["Last-Event-ID"]?.let { raw ->
            raw.toLongOrNull() ?: run {
                sendCursorStale(bus.oldestRetainedRevision() ?: 0L)
                return
            }
        }
    val oldestRetained = bus.oldestRetainedRevision()

    // Stale if client cursor is older than the bus's replay-buffer floor:
    // the bus has already evicted the events the client needs.
    // "clientCursor < oldestRetained" → stale; "null" → fresh subscriber, stream normally.
    if (lastEventId != null && oldestRetained != null && lastEventId < oldestRetained) {
        sendCursorStale(oldestRetained)
        return
    }

    // Comment-line keepalive every [heartbeatIntervalMillis] ms. The frames
    // (`:keepalive\r\n`) are ignored by the EventSource spec on the client
    // but keep the TCP connection alive across NATs and load balancers,
    // which otherwise drop idle streams. The side-job is cancelled in the
    // `finally` below so it never leaks across requests.
    //
    // Note: Ktor's built-in `heartbeat { }` extension was tried first but
    // its frames don't reach the client through `testApplication`'s SSE
    // transport — likely because `launch(heartbeatJob + ...)` reparents
    // outside the session's structured-concurrency tree. A manual launch
    // on the session's own scope is observable end-to-end.
    val heartbeatJob =
        launch {
            while (isActive) {
                delay(heartbeatIntervalMillis)
                send(ServerSentEvent(comments = "keepalive"))
            }
        }

    try {
        bus
            .subscribe()
            // Skip events the client already received. With replay=256, a reconnecting
            // client would otherwise see events from the replay cache that it already
            // processed in a previous session.
            .filter { it.event.revision > (lastEventId ?: 0L) }
            .collect { busEvent ->
                // Per-user scoping: a BusEvent carrying a userId belongs to a
                // user-scoped domain — deliver it only to that user. A null userId
                // is a global-domain event, delivered to every subscriber.
                if (busEvent.userId != null && busEvent.userId != userId) return@collect
                // Type-bound: repo and event match by construction, so the repo's
                // serializer is guaranteed to fit the event's payload type.
                send(
                    id = busEvent.event.revision.toString(),
                    event = busEvent.repo.domainName,
                    data = busEvent.repo.encodeSyncEventAsJson(busEvent.event),
                )
            }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val cid = UUID.randomUUID().toString()
        log.error(e) { "Uncaught SSE flow exception [cid=$cid]" }
        send(
            data =
                contractJson.encodeToString(
                    SyncControl.serializer(),
                    SyncControl.StreamError(
                        error =
                            InternalError(
                                correlationId = cid,
                                cause = e::class.simpleName,
                                debugInfo = null,
                            ),
                    ),
                ),
            event = SSE_EVENT_CONTROL,
        )
    } finally {
        heartbeatJob.cancel()
    }
}

/** Emits a [SyncControl.CursorStale] control frame on the firehose. */
private suspend fun ServerSSESession.sendCursorStale(lastKnownRevision: Long) {
    send(
        data =
            contractJson.encodeToString(
                SyncControl.serializer(),
                SyncControl.CursorStale(lastKnownRevision = lastKnownRevision),
            ),
        event = SSE_EVENT_CONTROL,
    )
}
