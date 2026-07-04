package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncDomainKey
import com.calypsan.listenup.api.sync.SyncPayload
import kotlin.reflect.KClass

/**
 * A declared sync domain — one entry in the catalog. Two kinds: [MirroredDomain]
 * (Room-mirrored via SSE fat events + catch-up + digest) and [RefreshedDomain]
 * (nudge-driven; no cursor, a declared refresh strategy fired on a named control).
 */
internal sealed interface ClientSyncDomain

/**
 * A Room-mirrored domain: SSE fat events + REST catch-up + (usually) digest
 * reconciliation. The descriptor is the domain's complete sync rulebook — transport
 * identity, apply seam, conflict policy, delete semantics, digest posture, write
 * tier, and access gating — declared in one value.
 */
internal class MirroredDomain<T : SyncPayload>(
    val key: SyncDomainKey<T>,
    val apply: MirrorApply<T>,
    val conflict: ConflictPolicy<T>,
    val deletes: DeleteSemantics,
    val digest: DigestParticipation,
    val writes: WriteTier,
    val accessGate: AccessGate? = null,
    /**
     * The stable sync id of a payload (matches the SSE envelope id). Defaults to the
     * payload's own [SyncPayload.id] — composite-key junctions expose that as their
     * synthetic `"a:b"` form, so the default covers every domain and no factory
     * overrides it.
     */
    val syncIdOf: (T) -> String = { it.id },
) : ClientSyncDomain

/**
 * A nudge-driven domain: no Room cursor and no digest. When the server pushes its
 * [trigger] control frame, the dispatcher runs [refresh]. Server-side emission of the
 * trigger is unchanged (it stays where it is, semantically owned by each feature).
 *
 * A nudge frame is lossy (replay=0): a subscriber not attached at emit time never sees it.
 * The self-heal is derived, not declared: the lifecycle-reconcile edge re-runs every
 * refreshed domain's [refresh] through [RefreshedDomainRouter.refreshAll], so a dropped
 * trigger heals on the next foreground/reconnect with no per-domain recovery wiring.
 */
internal class RefreshedDomain(
    val trigger: KClass<out SyncControl>,
    val refresh: RefreshStrategy,
) : ClientSyncDomain
