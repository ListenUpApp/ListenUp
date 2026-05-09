package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.Page
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.util.concurrent.ConcurrentHashMap

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

/**
 * Mounts the Sync Foundation REST endpoints under `/api/v1/sync`:
 *  - `GET /api/v1/sync/<domain>?since=<rev>&limit=<n>` — paginated catch-up
 *  - `GET /api/v1/sync/<domain>/digest?cursor=<rev>` — drift detection
 *
 * SSE firehose and domain-list endpoints are added in Tasks 17-18.
 */
fun Route.syncRoutes() {
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
}
