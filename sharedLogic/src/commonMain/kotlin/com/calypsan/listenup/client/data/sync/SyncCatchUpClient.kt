package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.DomainList
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.Tombstoned
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.suspendRunCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}
private const val PAGE_LIMIT = 500
private const val SERVER_URL_NOT_CONFIGURED = "Server URL not configured"

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
class SyncCatchUpClient(
    private val httpClientProvider: suspend () -> HttpClient,
    private val serverUrlProvider: suspend () -> String?,
    private val store: SyncCursorStore,
) : CatchUp {
    /** Drain catch-up for [handler]. Cursor advances incrementally per page. */
    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> =
        suspendRunCatching {
            val baseUrl = serverUrlProvider() ?: error(SERVER_URL_NOT_CONFIGURED)
            val httpClient = httpClientProvider()
            var since = store.getCursor(handler.domainName) ?: 0L
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
                for (item in page.items) {
                    val isTomb = (item as? Tombstoned)?.deletedAt != null
                    handler.onCatchUpItem(item, isTomb)
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
                for (item in page.items) {
                    val isTomb = (item as? Tombstoned)?.deletedAt != null
                    handler.onCatchUpItem(item, isTomb)
                    if (!isTomb) accessibleIds += handler.syncId(item)
                }
                val next = page.nextCursor
                if (next != null) since = next
                if (!page.hasMore) break
            }
            accessibleIds
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
