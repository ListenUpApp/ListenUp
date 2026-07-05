package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.AccessScope
import com.calypsan.listenup.core.currentEpochMilliseconds
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Re-derives and prunes the caller's access-gated domains after a `SyncControl.AccessChanged`
 * signal — the client half of the scoped-delta design. Extracted from [SyncEngine] (file-per-
 * concern) so the reconcile logic lives apart from the engine's lifecycle/coalesce plumbing.
 *
 * Two modes, chosen by the frame's [AccessScope]:
 *
 *  - **Coarse** ([reconcile] with `null`): the pre-scope behavior and the safe anchor for skew or
 *    frame loss. Every access-gated domain is re-derived from cursor 0 via
 *    [CatchUp.catchUpTransient] (server-access-filtered, so it returns exactly what the caller may
 *    now see) and [AccessFilteredSyncHandler.pruneTo] evicts every locally-live row not in that set.
 *
 *  - **Delta** ([reconcile] with a scope): O(changed) instead of O(library). Only the named
 *    entities are fetched — access-filtered — and pruned within the scope
 *    ([AccessFilteredSyncHandler.pruneWithin]), so a row the server returns is upserted and one it
 *    asked about but did not return is tombstoned, while a live row OUTSIDE the scope is never
 *    touched. Domains run in dependency order `collections → collection_books → books`; the books
 *    prune's `afterPrune` cascade drops readership + Continue-Listening positions for the now-dead
 *    books. `collection_shares` is revision-cursored and rides the coarse anchor / live tail;
 *    `activities` gains ride the digest backstop and losses ride the books `afterPrune` cascade —
 *    neither is fetched here.
 *
 * A delta pass ends in exactly the state a coarse pass restricted to the scope would: same upserts,
 * same tombstones, no leak. Per-domain failures are logged and swallowed — one domain must not
 * strand the others; the next signal (or the coarse anchor) recovers. The persisted
 * [SyncCursorStore] is never touched: the live SSE/cursor path continues independently.
 */
internal class AccessReconciler(
    private val registry: ClientSyncDomainRegistry,
    private val catchUp: CatchUp,
    private val now: () -> Long = { currentEpochMilliseconds() },
) {
    /** Re-derive access-gated domains: coarse when [scope] is null, otherwise a scoped delta. */
    suspend fun reconcile(scope: AccessScope?) {
        if (scope == null) reconcileCoarse() else reconcileDelta(scope)
    }

    /**
     * Whole-library re-derive: every access-gated domain re-pulled from 0 and pruned to the
     * returned accessible set. The semantic anchor — correct regardless of what changed.
     */
    private suspend fun reconcileCoarse() {
        logger.info { "AccessChanged (coarse): re-deriving + pruning every access-gated domain" }
        val ts = now()
        for (handler in registry.accessFilteredHandlers()) {
            @Suppress("UNCHECKED_CAST")
            val typed = handler as SyncDomainHandler<Any>
            when (val ids = catchUp.catchUpTransient(typed)) {
                is AppResult.Success -> {
                    (handler as AccessFilteredSyncHandler).pruneTo(ids.data, ts)
                }

                is AppResult.Failure -> {
                    logger.warn { "AccessChanged coarse reconcile failed for ${handler.domainName}: ${ids.error.code}" }
                }
            }
        }
    }

    /**
     * Scoped delta: fetch + prune only the named entities, in dependency order so a collection's
     * membership is reconciled before the books it gates. Each step is independent — a failure logs
     * and moves on; the coarse anchor backstops any gap.
     */
    private suspend fun reconcileDelta(scope: AccessScope) {
        logger.info {
            "AccessChanged (delta): ${scope.collectionIds.size} collection(s), ${scope.bookIds.size} book(s)"
        }
        val ts = now()
        fetchAndPruneById("collections", scope.collectionIds, ts)
        fetchAndPruneCollectionBooks(scope, ts)
        fetchAndPruneById("books", scope.bookIds, ts)
    }

    /**
     * Delta one own-id-keyed domain (`books` / `collections`): fetch [ids] access-filtered, upsert
     * what comes back, and [AccessFilteredSyncHandler.pruneWithin] the candidate set [ids] against
     * the returned accessible subset — so an id asked about but not returned is tombstoned, and
     * every row outside [ids] is left untouched.
     */
    private suspend fun fetchAndPruneById(
        domainName: String,
        ids: List<String>,
        ts: Long,
    ) {
        if (ids.isEmpty()) return
        val handler = accessGatedHandler(domainName) ?: return

        @Suppress("UNCHECKED_CAST")
        val typed = handler as SyncDomainHandler<Any>
        when (val returned = catchUp.fetchTransient(typed, TargetedFetch.ByIds(ids))) {
            is AppResult.Success -> {
                (handler as AccessFilteredSyncHandler).pruneWithin(ids.toSet(), returned.data, ts)
            }

            is AppResult.Failure -> {
                logger.warn { "AccessChanged delta fetch failed for $domainName: ${returned.error.code}" }
            }
        }
    }

    /**
     * Delta the `collection_books` domain by the scope's collections: fetch every membership of the
     * affected collections (access-filtered), then prune the LOCAL memberships of just those
     * collections against what came back. The candidate set is the local live membership rows whose
     * `collectionId` is in scope — derived from the synthetic `"$collectionId:$bookId"` wire id — so
     * a membership in a collection the delta never named can never be tombstoned.
     */
    private suspend fun fetchAndPruneCollectionBooks(
        scope: AccessScope,
        ts: Long,
    ) {
        if (scope.collectionIds.isEmpty()) return
        val handler = accessGatedHandler("collection_books") ?: return
        val gated = handler as AccessFilteredSyncHandler

        @Suppress("UNCHECKED_CAST")
        val typed = handler as SyncDomainHandler<Any>
        val scopeCols = scope.collectionIds.toSet()
        val candidateIds = gated.localLiveIds().filterTo(mutableSetOf()) { it.substringBefore(':') in scopeCols }
        when (val returned = catchUp.fetchTransient(typed, TargetedFetch.ByCollectionIds(scope.collectionIds))) {
            is AppResult.Success -> {
                gated.pruneWithin(candidateIds, returned.data, ts)
            }

            is AppResult.Failure -> {
                logger.warn { "AccessChanged delta fetch failed for collection_books: ${returned.error.code}" }
            }
        }
    }

    /** The registered handler for [domainName] if it is an [AccessFilteredSyncHandler]; else null (logged). */
    private fun accessGatedHandler(domainName: String): SyncDomainHandler<*>? {
        val handler = registry.lookup(domainName)
        if (handler !is AccessFilteredSyncHandler) {
            logger.warn { "AccessChanged delta: '$domainName' is not an access-gated handler; skipping" }
            return null
        }
        return handler
    }
}
