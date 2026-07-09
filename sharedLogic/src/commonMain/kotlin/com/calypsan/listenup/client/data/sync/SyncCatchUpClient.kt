package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.DomainList
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.Tombstoned
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.suspendRunCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

// Smaller pages trade a few more HTTP round-trips for a much lower peak heap: each page is decoded
// to a JsonElement tree AND a List<Payload> before it is applied, so a 500-book page is a large
// transient spike during onboarding. Stability over raw sync speed (a 1000-book initial population
// otherwise pushed the client to OOM).
private const val PAGE_LIMIT = 100
private const val SERVER_URL_NOT_CONFIGURED = "Server URL not configured"

// Per-request cap on a targeted `?ids=` / `?collectionIds=` fetch — matches the server's
// MAX_TARGETED_IDS. A scope larger than this is chunked, never truncated: a truncated response
// would read to the client as "these ids are gone" and wrongly tombstone still-accessible rows.
private const val TARGETED_FETCH_LIMIT = 100

/**
 * Drives REST `?since=<rev>` per-domain pagination for client sync catch-up.
 *
 * Iterates pages until `hasMore == false`, advancing the per-domain cursor in
 * [SyncCursorStore] after each page so a crash mid-pagination resumes from the
 * last drained page on next start. Tombstone routing (`isTombstone = true`) is
 * driven by the [Tombstoned] marker on the domain DTO — no per-domain switches.
 *
 * The flow is silent: no per-item progress events leak to UI. Only the cursor
 * advance is observable, and only via the cursor store's normal Room reactivity.
 *
 * Wire shape: `body<JsonElement>()` then manual decode via
 * [contractJson.decodeFromJsonElement] with the handler's payload serializer —
 * Ktor's reified `body<Page<T>>()` cannot carry the generic through type erasure.
 *
 * Constructor takes two suspend lambdas instead of concrete `HttpClient` /
 * base URL so production wiring (D1) passes method references and tests pass
 * any [HttpClient] + base URL — mirrors [SyncSseClient]'s shape.
 */
internal class SyncCatchUpClient(
    private val httpClientProvider: suspend () -> HttpClient,
    private val serverUrlProvider: suspend () -> String?,
    private val store: SyncCursorStore,
    private val transactionRunner: TransactionRunner,
) : CatchUp {
    /** Drain catch-up for [handler]. Cursor advances incrementally per page. */
    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> =
        pageFrom(handler, startSince = store.getCursor(handler.domainName) ?: 0L)

    /**
     * Re-pull [handler]'s domain from `since = 0`, applying every item and advancing the
     * persisted cursor. Used by the reconciler to repair a domain whose digest diverged.
     */
    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> =
        pageFrom(handler, startSince = 0L)

    /**
     * Core paging loop shared by [catchUp] and [catchUpFromZero]. Pulls pages of [handler]'s
     * domain starting from [startSince], applies each item via [SyncDomainHandler.onCatchUpItem],
     * and advances the persisted cursor after each page.
     */
    private suspend fun <T : Any> pageFrom(
        handler: SyncDomainHandler<T>,
        startSince: Long,
    ): AppResult<Unit> =
        suspendRunCatching {
            val baseUrl = serverUrlProvider() ?: error(SERVER_URL_NOT_CONFIGURED)
            val httpClient = httpClientProvider()
            var since = startSince
            while (true) {
                val element: JsonElement =
                    httpClient
                        .get("$baseUrl/api/v1/sync/${handler.domainName}?since=$since&limit=$PAGE_LIMIT")
                        .body()
                val page: Page<T> =
                    contractJson.decodeFromJsonElement(
                        Page.serializer(handler.payloadSerializer),
                        element,
                    )
                // Apply the whole page in ONE outer transaction. Each handler.onCatchUpItem still
                // runs its own atomically {} — those nest as savepoints, so per-item failures stay
                // isolated, but Room's invalidation tracker fires ONCE per page instead of once per
                // row. On a fresh library that turns ~1000 single-row commits (each re-running the
                // whole-table observe query → the onboarding GC storm) into a handful of page commits,
                // and books still stream in page-by-page.
                val failed =
                    transactionRunner.atomically {
                        var failures = 0
                        for (item in page.items) {
                            val isTomb = (item as? Tombstoned)?.deletedAt != null
                            if (handler.onCatchUpItem(item, isTomb) is AppResult.Failure) failures++
                        }
                        failures
                    }
                if (failed > 0) {
                    // The cursor still advances (idempotent upserts + a later reconcile re-pulls), but
                    // surface the loss rather than discarding it silently.
                    logger.warn { "catchUp(${handler.domainName}): $failed/${page.items.size} items failed to apply" }
                }
                page.nextCursor?.let {
                    store.setCursor(handler.domainName, it)
                    since = it
                }
                if (!page.hasMore) break
            }
        }

    /**
     * Page [handler]'s access-filtered catch-up from cursor 0 WITHOUT advancing
     * [SyncCursorStore]. Each item is applied via [SyncDomainHandler.onCatchUpItem]
     * (accessible rows are upserted/refreshed; server-returned tombstones soft-delete),
     * and the non-tombstone ids are collected via [SyncDomainHandler.syncId] into the
     * returned set — the caller's current accessible id set for the `AccessChanged` reconcile.
     *
     * Deliberately copies [catchUp]'s paging loop minus the `store.setCursor(...)` call: the
     * persisted cursor must not move, because the live SSE/cursor path continues independently.
     */
    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> =
        suspendRunCatching {
            val baseUrl = serverUrlProvider() ?: error(SERVER_URL_NOT_CONFIGURED)
            val httpClient = httpClientProvider()
            val accessibleIds = mutableSetOf<String>()
            var since = 0L
            while (true) {
                val element: JsonElement =
                    httpClient
                        .get("$baseUrl/api/v1/sync/${handler.domainName}?since=$since&limit=$PAGE_LIMIT")
                        .body()
                val page: Page<T> =
                    contractJson.decodeFromJsonElement(
                        Page.serializer(handler.payloadSerializer),
                        element,
                    )
                transactionRunner.atomically {
                    for (item in page.items) {
                        val isTomb = (item as? Tombstoned)?.deletedAt != null
                        handler.onCatchUpItem(item, isTomb)
                        if (!isTomb) accessibleIds += handler.syncId(item)
                    }
                }
                val next = page.nextCursor
                if (next != null) since = next
                if (!page.hasMore) break
            }
            accessibleIds
        }

    /**
     * Targeted access-filtered fetch of just [fetch]'s ids WITHOUT advancing [SyncCursorStore] —
     * the read half of the scoped `AccessChanged` delta. Chunks the id set under
     * [TARGETED_FETCH_LIMIT] (the server's per-request cap) so a large scope never truncates,
     * applies each returned row via [SyncDomainHandler.onCatchUpItem] (inheriting the same
     * ServerWins/EchoShielded guard as paged catch-up), and returns the non-tombstone ids that came
     * back — the still-accessible subset the caller diffs against to prune.
     */
    override suspend fun <T : Any> fetchTransient(
        handler: SyncDomainHandler<T>,
        fetch: TargetedFetch,
    ): AppResult<Set<String>> =
        suspendRunCatching {
            val baseUrl = serverUrlProvider() ?: error(SERVER_URL_NOT_CONFIGURED)
            val httpClient = httpClientProvider()
            val (paramName, values) =
                when (fetch) {
                    is TargetedFetch.ByIds -> "ids" to fetch.ids
                    is TargetedFetch.ByCollectionIds -> "collectionIds" to fetch.collectionIds
                    is TargetedFetch.ByBookIds -> "bookIds" to fetch.bookIds
                }
            val returnedIds = mutableSetOf<String>()
            for (chunk in values.distinct().chunked(TARGETED_FETCH_LIMIT)) {
                val csv = chunk.joinToString(",")
                val element: JsonElement =
                    httpClient
                        .get("$baseUrl/api/v1/sync/${handler.domainName}?$paramName=$csv")
                        .body()
                val page: Page<T> =
                    contractJson.decodeFromJsonElement(
                        Page.serializer(handler.payloadSerializer),
                        element,
                    )
                transactionRunner.atomically {
                    for (item in page.items) {
                        val isTomb = (item as? Tombstoned)?.deletedAt != null
                        handler.onCatchUpItem(item, isTomb)
                        if (!isTomb) returnedIds += handler.syncId(item)
                    }
                }
            }
            returnedIds
        }

    /**
     * Iterate every registered domain in [registry] and run [catchUp] on each,
     * in registration order. Per-domain failures are logged but do not abort
     * the loop — one slow domain shouldn't strand the rest.
     */
    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> =
        suspendRunCatching {
            for (domainName in registry.registeredDomains()) {
                val handler = registry.lookup(domainName) ?: continue

                @Suppress("UNCHECKED_CAST")
                val typed = handler as SyncDomainHandler<Any>
                val result = catchUp(typed)
                if (result is AppResult.Failure) {
                    logger.warn { "catchUp($domainName) failed: ${result.error.code}" }
                }
            }
        }

    /** Server-side domain discovery via `GET /api/v1/sync/domains`. */
    override suspend fun domains(): AppResult<List<String>> =
        suspendRunCatching {
            val baseUrl = serverUrlProvider() ?: error(SERVER_URL_NOT_CONFIGURED)
            val httpClient = httpClientProvider()
            httpClient.get("$baseUrl/api/v1/sync/domains").body<DomainList>().domains
        }
}
