package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.AccessScope
import com.calypsan.listenup.client.data.sync.domains.AccessDeltaPolicy
import com.calypsan.listenup.client.data.sync.domains.ScopeAxis
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
 *  - **Delta** ([reconcile] with a scope): O(changed) instead of O(library). Which domains the delta
 *    fetches is REGISTRY-DRIVEN, not hardcoded: it iterates every access-gated handler and reads its
 *    [AccessDeltaPolicy] (declared on the domain's gate, no default), so a new gated domain cannot be
 *    forgotten here. Each [AccessDeltaPolicy.Targeted] domain is fetched — access-filtered — and
 *    pruned within its scope ([AccessFilteredSyncHandler.pruneWithin]), so a row the server returns is
 *    upserted and one it asked about but did not return is tombstoned, while a live row OUTSIDE the
 *    scope is never touched. Domains run in the policy's declared `order` (`collections →
 *    collection_books → books → activities`); the books prune's `afterPrune` cascade drops readership
 *    + Continue-Listening positions for the now-dead books, and `activities` self-prunes against its
 *    own book-scoped candidate set. `collection_shares` is [AccessDeltaPolicy.LiveTailOnly] — it is
 *    revision-cursored and rides the coarse anchor / live tail, so the delta never fetches it.
 *
 * A delta pass ends in exactly the state a coarse pass restricted to the scope would: same upserts,
 * same tombstones, no leak. A per-domain failure never strands the others — the pass continues — but
 * it is no longer swallowed: the failed remainder is reported to the caller via
 * [AccessReconcileOutcome] so the engine can re-queue it for exactly one bounded retry. After that
 * one retry, the reconnect / coarse anchor remains the convergence backstop. The persisted
 * [SyncCursorStore] is never touched: the live firehose/cursor path continues independently.
 */
internal class AccessReconciler(
    private val registry: ClientSyncDomainRegistry,
    private val catchUp: CatchUp,
    private val now: () -> Long = { currentEpochMilliseconds() },
) {
    /** Re-derive access-gated domains: coarse when [scope] is null, otherwise a scoped delta. */
    suspend fun reconcile(scope: AccessScope?): AccessReconcileOutcome =
        if (scope == null) reconcileCoarse() else reconcileDelta(scope)

    /**
     * Whole-library re-derive: every access-gated domain re-pulled from 0 and pruned to the
     * returned accessible set. The semantic anchor — correct regardless of what changed. A failed
     * domain does not stop the others; if any failed, the whole pass is re-queued as coarse.
     */
    private suspend fun reconcileCoarse(): AccessReconcileOutcome {
        logger.info { "AccessChanged (coarse): re-deriving + pruning every access-gated domain" }
        val ts = now()
        var anyFailure = false
        for (handler in registry.accessFilteredHandlers()) {
            @Suppress("UNCHECKED_CAST")
            val typed = handler as SyncDomainHandler<Any>
            when (val ids = catchUp.catchUpTransient(typed)) {
                is AppResult.Success -> {
                    (handler as AccessFilteredSyncHandler).pruneTo(ids.data, ts)
                }

                is AppResult.Failure -> {
                    logger.warn { "AccessChanged coarse reconcile failed for ${handler.domainName}: ${ids.error.code}" }
                    anyFailure = true
                }
            }
        }
        return if (anyFailure) AccessReconcileOutcome.Requeue(null) else AccessReconcileOutcome.Clean
    }

    /**
     * Scoped delta: iterate every access-gated handler, keep those whose [AccessDeltaPolicy] is
     * [AccessDeltaPolicy.Targeted], and run them in the declared `order` so a collection's membership
     * is reconciled before the books it gates. Each domain fetches over its axis's id list and prunes
     * its own candidate set; a failure logs and moves on, folding that axis's ids into the failed
     * remainder so the engine can re-queue exactly that portion. The coarse anchor backstops any gap
     * after the one retry.
     */
    private suspend fun reconcileDelta(scope: AccessScope): AccessReconcileOutcome {
        logger.info {
            "AccessChanged (delta): ${scope.collectionIds.size} collection(s), ${scope.bookIds.size} book(s)"
        }
        val ts = now()
        val failedCollectionIds = mutableSetOf<String>()
        val failedBookIds = mutableSetOf<String>()

        val targeted =
            registry
                .accessFilteredHandlers()
                .mapNotNull { handler ->
                    ((handler as AccessFilteredSyncHandler).deltaPolicy as? AccessDeltaPolicy.Targeted)
                        ?.let { handler to it }
                }.sortedBy { (_, policy) -> policy.order }

        for ((handler, policy) in targeted) {
            val ids =
                when (policy.axis) {
                    ScopeAxis.Collections -> scope.collectionIds
                    ScopeAxis.Books -> scope.bookIds
                }
            if (ids.isEmpty()) continue

            @Suppress("UNCHECKED_CAST")
            val typed = handler as SyncDomainHandler<Any>
            when (val returned = catchUp.fetchTransient(typed, policy.fetchFor(ids))) {
                is AppResult.Success -> {
                    (handler as AccessFilteredSyncHandler).pruneWithin(policy.candidatesFor(ids), returned.data, ts)
                }

                is AppResult.Failure -> {
                    logger.warn { "AccessChanged delta fetch failed for ${handler.domainName}: ${returned.error.code}" }
                    when (policy.axis) {
                        ScopeAxis.Collections -> failedCollectionIds += scope.collectionIds
                        ScopeAxis.Books -> failedBookIds += scope.bookIds
                    }
                }
            }
        }

        return if (failedCollectionIds.isEmpty() && failedBookIds.isEmpty()) {
            AccessReconcileOutcome.Clean
        } else {
            AccessReconcileOutcome.Requeue(AccessScope(failedCollectionIds.toList(), failedBookIds.toList()))
        }
    }
}

/**
 * What an [AccessReconciler.reconcile] pass could not complete. The engine folds a
 * [Requeue] back into its coalescer for exactly one follow-up attempt; a `null`
 * [Requeue.retryScope] re-queues a coarse pass (the accumulator's poison semantics).
 */
internal sealed interface AccessReconcileOutcome {
    /** Every fetch succeeded — nothing to retry. */
    data object Clean : AccessReconcileOutcome

    /** At least one fetch failed; [retryScope] is the failed remainder (`null` = coarse). */
    data class Requeue(
        val retryScope: AccessScope?,
    ) : AccessReconcileOutcome
}
