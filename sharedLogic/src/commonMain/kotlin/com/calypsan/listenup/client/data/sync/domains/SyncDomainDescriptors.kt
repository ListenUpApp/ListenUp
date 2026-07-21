package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncDomainKey
import com.calypsan.listenup.api.sync.SyncPayload
import kotlin.reflect.KClass

/*
 * A declared sync domain is one of two standalone descriptor kinds. The catalog keeps them
 * in separate typed lists ([SyncDomainCatalog.mirrored] / [SyncDomainCatalog.refreshed]) and
 * nothing consumes them polymorphically, so they share no supertype:
 *  - [MirroredDomain]  — Room-mirrored via firehose fat events + catch-up + digest.
 *  - [RefreshedDomain] — refresh-driven; no cursor, a declared refresh strategy fired on a
 *    named control.
 */

/**
 * A Room-mirrored domain: firehose fat events + REST catch-up + (usually) digest
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
     * The stable sync id of a payload (matches the firehose envelope id). Defaults to the
     * payload's own [SyncPayload.id] — composite-key junctions expose that as their
     * synthetic `"a:b"` form, so the default covers every domain and no factory
     * overrides it.
     */
    val syncIdOf: (T) -> String = { it.id },
)

/**
 * A refresh-driven domain: no Room cursor and no digest. When the server pushes its
 * [trigger] control frame, the dispatcher runs [refresh]. Server-side emission of the
 * trigger is unchanged (it stays where it is, semantically owned by each feature).
 *
 * A refresh trigger is lossy (replay=0): a subscriber not attached at emit time never sees it.
 * The self-heal is derived, not declared: the lifecycle-reconcile edge re-runs every
 * refreshed domain's [refresh] through [RefreshedDomainRouter.refreshAll], so a dropped
 * trigger heals on the next foreground/reconnect with no per-domain recovery wiring.
 *
 * [refreshOnAccessChanged] is the second, orthogonal recovery edge: when `true`, the engine also
 * re-runs this domain's [refresh] at the end of an `AccessChanged` reconcile (via
 * [RefreshedDomainRouter.refreshAccessSensitive]). It declares "this refresh reads an ACL-filtered
 * surface, so a change to what the caller can access is a change to what this refresh returns."
 * The refresh runs after the reconcile's leader guard is released, so a flagged [refresh] must be
 * idempotent and concurrency-safe — presence's `Ping` is; a `Refetch` would need its own single-flight.
 * Defaults `false` — most refreshed domains (server info, preferences) are access-insensitive.
 */
internal class RefreshedDomain(
    val trigger: KClass<out SyncControl>,
    val refresh: RefreshStrategy,
    val refreshOnAccessChanged: Boolean = false,
)
