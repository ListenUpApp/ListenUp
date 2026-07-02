package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.SyncDomainKey

/**
 * A declared sync domain — one entry in the catalog. Phase 3 adds the second kind
 * (`RefreshedDomain`, nudge-driven); until then [MirroredDomain] is the only variant.
 */
internal sealed interface ClientSyncDomain

/**
 * A Room-mirrored domain: SSE fat events + REST catch-up + (usually) digest
 * reconciliation. The descriptor is the domain's complete sync rulebook — transport
 * identity, apply seam, conflict policy, delete semantics, digest posture, write
 * tier, and access gating — declared in one value.
 */
internal class MirroredDomain<T : Any>(
    val key: SyncDomainKey<T>,
    /** The stable sync id of a payload (matches the SSE envelope id). */
    val syncIdOf: (T) -> String,
    val apply: MirrorApply<T>,
    val conflict: ConflictPolicy<T>,
    val deletes: DeleteSemantics,
    val digest: DigestParticipation,
    val writes: WriteTier,
    val accessGate: AccessGate? = null,
) : ClientSyncDomain
