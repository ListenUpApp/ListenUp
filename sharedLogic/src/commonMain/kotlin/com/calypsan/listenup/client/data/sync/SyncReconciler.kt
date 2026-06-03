package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private val logger = KotlinLogging.logger {}

/**
 * After each connect, fingerprints every registered domain at the global
 * [SyncCursorStore.highestCursor] and re-pulls (from zero) any domain whose digest diverges
 * from the server's — closing the firehose gap where a domain below the global floor was
 * delivered by neither catch-up nor SSE.
 *
 * The cursor is captured once at the top of [reconcileAll] so every domain gets a stable,
 * identical comparison point regardless of live SSE events arriving concurrently. Domains
 * are reconciled in parallel. Per-domain failures are logged and skipped; reconciliation
 * never throws into the engine or blocks the live stream.
 */
class SyncReconciler(
    private val registry: ClientSyncDomainRegistry,
    private val store: SyncCursorStore,
    private val digestClient: DomainDigestClient,
    private val catchUp: CatchUp,
) {
    /**
     * Reconcile all registered domains at the current [SyncCursorStore.highestCursor].
     * Returns immediately if no cursor has been stored yet (i.e. the client has never
     * completed any catch-up pass).
     */
    suspend fun reconcileAll() {
        val max = store.highestCursor() ?: return
        val handlers = registry.registeredDomains().mapNotNull { registry.lookup(it) }
        coroutineScope {
            handlers
                .map { handler ->
                    @Suppress("UNCHECKED_CAST")
                    val typed = handler as SyncDomainHandler<Any>
                    async { reconcileOne(typed, max) }
                }.awaitAll()
        }
    }

    /**
     * Reconcile a single domain: compute the local digest at [max], fetch the server digest,
     * and trigger a full re-pull if they differ.
     *
     * Callers cast [SyncDomainHandler]<*> to [SyncDomainHandler]<Any> before calling here,
     * mirroring the [SyncCatchUpClient.catchUpAll] pattern. The generic type parameter is not
     * observed inside this method — only [SyncDomainHandler.domainName] and
     * [SyncDomainHandler.localDigestRows] are called, both of which are type-parameter-agnostic.
     */
    private suspend fun <T : Any> reconcileOne(
        handler: SyncDomainHandler<T>,
        max: Long,
    ) {
        try {
            val rows = handler.localDigestRows(max) ?: return // null = opted out
            val local = DigestComputer.compute(max, rows)
            val remote =
                when (val r = digestClient.fetch(handler.domainName, max)) {
                    is AppResult.Success -> {
                        r.data
                    }

                    is AppResult.Failure -> {
                        logger.warn { "Digest fetch failed for ${handler.domainName}: ${r.error.code}" }
                        return
                    }
                }
            if (local.count != remote.count || local.hash != remote.hash) {
                logger.info { "Digest drift on ${handler.domainName}; re-pulling from 0" }
                catchUp.catchUpFromZero(handler)
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Reconcile failed for ${handler.domainName}; skipping" }
        }
    }
}
