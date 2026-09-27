package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val logger = KotlinLogging.logger {}

/**
 * The outbox drain path's two "re-fetch current server truth for specific entities, by id" passes.
 *
 * Both run only AFTER [PendingOperationQueue.drain] has returned — [SyncEngine.runDrain] calls
 * [reconcileSentEntities] once the drain wave completes, and [SyncEngine]'s heal-drain collector
 * calls [healEntity] per dead-lettered/dismissed op — so neither ever holds the queue's own drain
 * mutex. Both serialize their server pull on the SAME [catchUpMutex] that [SyncEngine] uses for every
 * other cursor-advancing catch-up/reconcile, which is passed in (not owned here) so that single
 * serialization guarantee is preserved across the split. Extracted from [SyncEngine] to keep that
 * class focused on connection + orchestration lifecycle.
 */
internal class DrainReconciler(
    private val queue: PendingOperationQueue,
    private val registry: ClientSyncDomainRegistry,
    private val catchUp: CatchUp,
    private val catchUpMutex: Mutex,
) {
    /**
     * Re-fetch current server truth for [ref]'s entity and apply it over a never-accepted optimistic
     * edit (DRIFT-1). Triggered when an outbox op dead-lettered or was dismissed: the local row's
     * `(id, revision)` is unchanged, so no digest reconcile, `?since=` catch-up, or firehose echo repairs
     * a content-only divergence — only a targeted re-fetch does, translated from the op's key through
     * [SyncDomainHandler.refetchForOutboxKey] (skipped when the domain declares none can serve it).
     * The equal-revision re-fetch applies through the normal `ServerWins` strict-`>` guard (equal
     * revisions are not stale), so no force override is needed.
     *
     * Gated on [PendingOperationQueue.hasQueuedOpFor] so a fresh in-flight edit for the same entity
     * is never clobbered — its own echo is the authority then. Runs the fetch under [catchUpMutex]
     * like every other server pull. Unlike [reconcileSentEntities], this does NOT skip ungated
     * (userScoped/global) domains: the server's by-id read now serves the curation domains, which is
     * exactly where this heal is needed.
     */
    suspend fun healEntity(ref: SentEntityRef) {
        if (queue.hasQueuedOpFor(ref.domainName, ref.entityId)) return
        val handler =
            registry.lookup(ref.domainName) ?: run {
                logger.debug { "heal: '${ref.domainName}' has no sync handler; skipping (client-only channel)" }
                return
            }

        val fetch =
            handler.refetchForOutboxKey(ref.entityId) ?: run {
                logger.debug { "heal: '${ref.domainName}' has no targeted fetch for ${ref.entityId}; skipping" }
                return
            }

        @Suppress("UNCHECKED_CAST")
        val typed = handler as SyncDomainHandler<Any>
        val result = catchUpMutex.withLock { catchUp.fetchTransient(typed, fetch) }
        if (result is AppResult.Failure) {
            logger.warn {
                "heal fetch failed for '${ref.domainName}' entity ${ref.entityId}: ${result.error.code}; " +
                    "the phantom persists until the next heal trigger"
            }
        }
    }

    /**
     * Targeted-reconcile the entities an outbox drain wave just sent, so a server echo the in-flight
     * anti-flicker shield dropped lands promptly instead of waiting for the next lifecycle digest.
     *
     * Bounded to ONE targeted fetch per access-gated domain per wave: the sent refs are grouped by
     * domain, translated through [SyncDomainHandler.refetchForOutboxKey] (an op key is not always a wire
     * id), and fetched via the existing [CatchUp.fetchTransient] targeted path —
     * the same primitive the `AccessChanged` delta uses, so the fetch inherits the domain handler's
     * revision guard and in-flight shield. Two classes are skipped:
     *  - A client-only channel (`profile`/`preferences`) has no registered [SyncDomainHandler] — its
     *    inbound echo rides a different surface (the `public_profiles` mirror / `PreferencesChanged`).
     *  - A non-[AccessFilteredSyncHandler] domain (userScoped/global — positions, tags, series, …)
     *    can't be served by the server's access-filtered `pullByIds` (no wired driver), so a fetch
     *    would 500 and buy nothing; its echo converges via `?since=` catch-up / newer-wins instead.
     *
     * Runs under the shared [catchUpMutex] so it serializes with catch-up/reconcile paging (never
     * concurrent), and NOT under [PendingOperationQueue.drain]'s own mutex (already released by the
     * caller before this runs, since `queue.drain()` has returned) so there is no lock-order
     * inversion. Best-effort: a failed fetch logs and moves on — the digest reconcile is the
     * convergence backstop.
     */
    suspend fun reconcileSentEntities(sentEntities: List<SentEntityRef>) {
        if (sentEntities.isEmpty()) return
        for ((domainName, refs) in sentEntities.groupBy { it.domainName }) {
            val handler = registry.lookup(domainName)
            if (handler == null) {
                logger.debug {
                    "reconcile-on-drain: '$domainName' has no sync handler (client-only channel); skipping targeted fetch"
                }
                continue
            }
            // The targeted `?ids=` fetch is the read half of the scoped AccessChanged delta — an
            // access-gated-domain primitive. Only [AccessFilteredSyncHandler] domains (books,
            // collections, collection_books) can be served by the server's access-filtered
            // `pullByIds`; a userScoped/global domain has no server-side driver, so the fetch would
            // 500 (and buy nothing — those echoes self-heal via `?since=` catch-up / newer-wins).
            // Skip them: reconcile-on-drain is a promptness optimization, and the digest reconcile is
            // the convergence backstop.
            if (handler !is AccessFilteredSyncHandler) {
                logger.debug {
                    "reconcile-on-drain: '$domainName' is not access-gated; skipping targeted fetch " +
                        "(its echo converges via catch-up / newer-wins)"
                }
                continue
            }
            val fetches = refs.mapNotNull { handler.refetchForOutboxKey(it.entityId) }.merged()

            @Suppress("UNCHECKED_CAST")
            val typed = handler as SyncDomainHandler<Any>
            for (fetch in fetches) {
                val result = catchUpMutex.withLock { catchUp.fetchTransient(typed, fetch) }
                if (result is AppResult.Failure) {
                    logger.warn {
                        "reconcile-on-drain fetch failed for '$domainName' ($fetch): ${result.error.code}; " +
                            "digest reconcile is the backstop"
                    }
                }
            }
        }
    }
}

/** One fetch per kind, its values de-duplicated — so a wave's refs cost one fetch per match column. */
private fun List<TargetedFetch>.merged(): List<TargetedFetch> {
    val ids = filterIsInstance<TargetedFetch.ByIds>().flatMap { it.ids }.distinct()
    val collectionIds = filterIsInstance<TargetedFetch.ByCollectionIds>().flatMap { it.collectionIds }.distinct()
    val bookIds = filterIsInstance<TargetedFetch.ByBookIds>().flatMap { it.bookIds }.distinct()
    return listOfNotNull(
        ids.takeIf { it.isNotEmpty() }?.let(TargetedFetch::ByIds),
        collectionIds.takeIf { it.isNotEmpty() }?.let(TargetedFetch::ByCollectionIds),
        bookIds.takeIf { it.isNotEmpty() }?.let(TargetedFetch::ByBookIds),
    )
}
