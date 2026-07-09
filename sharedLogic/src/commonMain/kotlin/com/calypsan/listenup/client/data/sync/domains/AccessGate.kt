package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.client.data.sync.TargetedFetch

/**
 * Access-gating hooks for domains whose server `pullSince` is filtered to the
 * caller's accessible set. Presence of a gate on a [MirroredDomain] makes its
 * composed handler implement `AccessFilteredSyncHandler`, so the engine's
 * `AccessChanged` reconcile discovers it via the registry.
 *
 * A gate declares only queries; it never computes the doomed set. The composed
 * handler ([AccessFilteredComposedSyncDomainHandler]) diffs [liveIds] against the
 * accessible set, chunks the result under [SQLITE_IN_CHUNK], and drives
 * [tombstoneByIds] + [afterPrune] — so every gate is two DAO method references (plus
 * an optional cascade), and the bind-variable ceiling is closed uniformly for all of
 * them, not latently per domain.
 *
 * [delta] is REQUIRED with no default: every access-gated domain must declare how it
 * participates in the scoped `AccessChanged` delta, so a new gated domain cannot compile
 * without a decision — the delta path can never silently forget a domain the way a
 * hardcoded fetch list could.
 */
internal class AccessGate(
    /** Ids of every live (non-tombstoned) local row, in wire-id form. */
    val liveIds: suspend () -> List<String>,
    /** Tombstone the given live local rows by wire id (called per bounded chunk). */
    val tombstoneByIds: suspend (ids: List<String>, now: Long) -> Unit,
    /** How this domain participates in the scoped `AccessChanged` delta — see [AccessDeltaPolicy]. */
    val delta: AccessDeltaPolicy,
    /**
     * Optional cascade run once after ANY prune (coarse or scoped) completes — the sweep for pure
     * caches that have no sync domain of their own (e.g. books' readership + Continue-Listening
     * positions). Safe to run in both paths because these rows are re-fetched, never digested.
     */
    val afterPrune: suspend () -> Unit = {},
)

/**
 * How a domain participates in the SCOPED `AccessChanged` delta — the per-gate declaration the
 * reconciler reads to decide whether, and how, to fetch and prune that domain for a targeted change.
 *
 * The reconciler iterates every access-gated handler and branches on this value alone, so adding a
 * new gated domain forces a delta decision at compile time (the gate's [AccessGate.delta] has no
 * default). The whole-library coarse path is unaffected — it always sweeps every gated domain.
 */
internal sealed interface AccessDeltaPolicy {
    /**
     * The domain IS fetched in the delta pass: pull the rows named by [fetchFor] over the [axis] id
     * list, then [AccessFilteredSyncHandler.pruneWithin] the [candidatesFor] set against what came
     * back. [order] fixes dependency order (a collection's membership reconciles before the books it
     * gates); a requested id that does not come back is tombstoned, one outside [candidatesFor] never.
     */
    class Targeted(
        val order: Int,
        val axis: ScopeAxis,
        val fetchFor: (ids: List<String>) -> TargetedFetch,
        val candidatesFor: suspend (ids: List<String>) -> Set<String>,
    ) : AccessDeltaPolicy

    /**
     * The domain is NOT fetched in the delta pass: its rows are revision-cursored and converge via
     * the live SSE tail, with the coarse anchor as the frame-loss backstop. [rationale] records why.
     */
    class LiveTailOnly(
        val rationale: String,
    ) : AccessDeltaPolicy
}

/** Which [com.calypsan.listenup.api.sync.AccessScope] id list keys a [AccessDeltaPolicy.Targeted] fetch. */
internal enum class ScopeAxis {
    Collections,
    Books,
}

/**
 * SQLite's compiled bind-variable ceiling is ~32,766; the access-gate prune chunks the
 * doomed-id set well under it before each `IN (:ids)` tombstone. `activities` is the only
 * gated domain whose append-forever growth can approach the ceiling, but chunking every
 * gate's prune closes the latent overflow uniformly at no cost to the smaller ones.
 */
internal const val SQLITE_IN_CHUNK = 900
