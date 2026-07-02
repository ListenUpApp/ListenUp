package com.calypsan.listenup.client.data.sync.domains

/**
 * Access-gating hooks for domains whose server `pullSince` is filtered to the
 * caller's accessible set. Presence of a gate on a [MirroredDomain] makes its
 * composed handler implement `AccessFilteredSyncHandler`, so the engine's
 * `AccessChanged` reconcile discovers it via the registry.
 */
internal class AccessGate(
    /** Ids of every live (non-tombstoned) local row, in wire-id form. */
    val localLiveIds: suspend () -> Set<String>,
    /** Tombstone every live local row NOT in the accessible set. */
    val pruneTo: suspend (accessibleIds: Set<String>, now: Long) -> Unit,
)
