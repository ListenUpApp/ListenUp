package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.DomainList
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject

/**
 * Per-domain registry populated by [SyncableRepository] init blocks.
 * REST + digest + domain-list routes look up repositories by name here.
 *
 * This is the static-registry style used in the RPC Exception Guard's
 * `RpcGuard.dispatch()` — small startup wart, but explicit: every
 * repository announces itself.
 */
internal object SyncRoutes {
    private val registry = ConcurrentHashMap<String, SyncableRepository<*, *>>()

    fun register(
        domainName: String,
        repository: SyncableRepository<*, *>,
    ) {
        registry[domainName] = repository
    }

    fun lookup(domainName: String): SyncableRepository<*, *>? = registry[domainName]

    fun knownDomains(): List<String> = registry.keys.sorted()
}

private val log = KotlinLogging.logger("rpc.SyncFirehose")

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

    // SSE firehose — streams every domain's BusEvents in real time.
    // event: line carries the domain name; id: line carries the revision (for Last-Event-Id).
    // Cursor-stale detection: if the client provides a Last-Event-Id newer than anything in
    // the bus's live-tail buffer, emit SyncControl.CursorStale and close.
    sse("/api/v1/sync/events") {
        val lastEventId = call.request.headers["Last-Event-ID"]?.toLongOrNull()
        val oldestRetained = bus.oldestRetainedRevision()

        // Stale if client cursor is older than the bus's replay-buffer floor:
        // the bus has already evicted the events the client needs.
        // "clientCursor < oldestRetained" → stale; "null" → fresh subscriber, stream normally.
        val isStale = lastEventId != null && oldestRetained != null && lastEventId < oldestRetained

        if (isStale) {
            send(
                data =
                    contractJson.encodeToString(
                        SyncControl.serializer(),
                        SyncControl.CursorStale(lastKnownRevision = oldestRetained),
                    ),
                event = "control",
            )
            return@sse
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
                event = "control",
            )
        } finally {
            heartbeatJob.cancel()
        }
    }

    get("/api/v1/sync/{domain}") {
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
            SyncRoutes.lookup(domainName)
                ?: return@get call.respond(HttpStatusCode.NotFound, "unknown domain: $domainName")

        @Suppress("UNCHECKED_CAST")
        val typedRepo = repo as SyncableRepository<Any, Any>
        val page: Page<Any> = typedRepo.pullSince(since, limit)
        // call.respond(page) would fail at runtime: kotlinx.serialization cannot
        // infer the concrete element serializer from the type-erased Page<Any>.
        // encodePageAsJson uses the concrete KSerializer<T> each repository provides.
        call.respondText(typedRepo.encodePageAsJson(page), ContentType.Application.Json)
    }

    get("/api/v1/sync/{domain}/digest") {
        val domainName =
            call.parameters["domain"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "missing domain")
        val cursor =
            call.request.queryParameters["cursor"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "cursor must be a Long")
        val repo =
            SyncRoutes.lookup(domainName)
                ?: return@get call.respond(HttpStatusCode.NotFound, "unknown domain: $domainName")

        @Suppress("UNCHECKED_CAST")
        val typedRepo = repo as SyncableRepository<Any, Any>
        val digest: DomainDigest = typedRepo.digest(cursor)
        call.respond(digest)
    }

    get("/api/v1/sync/domains") {
        call.respond(DomainList(domains = SyncRoutes.knownDomains()))
    }
}
