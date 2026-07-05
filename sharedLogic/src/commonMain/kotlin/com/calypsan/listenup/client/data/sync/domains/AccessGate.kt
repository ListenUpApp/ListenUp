package com.calypsan.listenup.client.data.sync.domains

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
 */
internal class AccessGate(
    /** Ids of every live (non-tombstoned) local row, in wire-id form. */
    val liveIds: suspend () -> List<String>,
    /** Tombstone the given live local rows by wire id (called per bounded chunk). */
    val tombstoneByIds: suspend (ids: List<String>, now: Long) -> Unit,
    /**
     * Optional cascade run once after ANY prune (coarse or scoped) completes — the sweep for pure
     * caches that have no sync domain of their own (e.g. books' readership + Continue-Listening
     * positions). Safe to run in both paths because these rows are re-fetched, never digested.
     */
    val afterPrune: suspend () -> Unit = {},
    /**
     * Optional cascade run once after a SCOPED prune ([AccessFilteredSyncHandler.pruneWithin]) only,
     * receiving that prune's tombstone timestamp. Reserved for dependents that ARE their own
     * access-gated sync domain — e.g. `activities`, which the coarse path re-derives authoritatively
     * via its own prune, but a scoped delta never fetches. Running this in the coarse path would
     * double-tombstone against that domain's own re-derivation, so it fires on the delta path alone.
     */
    val afterScopedPrune: suspend (now: Long) -> Unit = {},
)

/**
 * SQLite's compiled bind-variable ceiling is ~32,766; the access-gate prune chunks the
 * doomed-id set well under it before each `IN (:ids)` tombstone. `activities` is the only
 * gated domain whose append-forever growth can approach the ceiling, but chunking every
 * gate's prune closes the latent overflow uniformly at no cost to the smaller ones.
 */
internal const val SQLITE_IN_CHUNK = 900
