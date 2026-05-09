package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.DomainList
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.Tombstoned
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.suspendRunCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}
private const val PAGE_LIMIT = 500

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
 */
class SyncCatchUpClient(
    private val httpClient: HttpClient,
    private val store: SyncCursorStore,
    private val baseUrl: String,
) {
    /** Drain catch-up for [handler]. Cursor advances incrementally per page. */
    suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> =
        suspendRunCatching {
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
     * Iterate every registered domain in [registry] and run [catchUp] on each,
     * in registration order. Per-domain failures are logged but do not abort
     * the loop — one slow domain shouldn't strand the rest.
     */
    suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> =
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
    suspend fun domains(): AppResult<List<String>> =
        suspendRunCatching {
            httpClient.get("$baseUrl/api/v1/sync/domains").body<DomainList>().domains
        }
}
