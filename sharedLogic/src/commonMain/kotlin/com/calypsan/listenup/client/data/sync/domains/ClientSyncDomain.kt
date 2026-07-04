package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncDomainKey
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
    /** Staleness guard for inbound applies; null = domain opts out (AppendOnly/NewerWins). */
    val revisionGuard: RevisionGuard<T>? = null,
) : ClientSyncDomain

/**
 * A nudge-driven domain: no Room cursor and no digest. When the server pushes its
 * [trigger] control frame, the dispatcher runs [refresh]. Server-side emission of the
 * trigger is unchanged (it stays where it is, semantically owned by each feature).
 *
 * A nudge frame is lossy (replay=0): a subscriber not attached at emit time never sees it.
 * [recovery] is the declared, compile-time-required answer to "how does a DROPPED trigger
 * self-heal without a restart" — the lifecycle-reconcile pass executes it, so it is the
 * wiring, not a comment. A future nudge domain cannot exist without stating how it heals.
 */
internal class RefreshedDomain(
    val trigger: KClass<out SyncControl>,
    val refresh: RefreshStrategy,
    val recovery: NudgeRecovery,
) : ClientSyncDomain

/**
 * How a [RefreshedDomain]'s dropped nudge self-heals without an app restart. Required on every
 * nudge domain (Plan §6a) — the lifecycle-reconcile edge runs the declared recovery.
 */
internal sealed interface NudgeRecovery {
    /** Re-runs the domain's refresh on every lifecycle-reconcile edge (reconnect + foreground). */
    data object OnLifecycleReconcile : NudgeRecovery

    /**
     * [OnLifecycleReconcile] plus the consumer refetches on collector subscribe, with an optional
     * slow poll while a collector stays subscribed ([pollWhileSubscribedMs], null = no poll).
     */
    data class OnSubscribeAndReconcile(
        /** Slow-poll interval while a collector stays subscribed; null = no poll. Wired in Phase 3 — read by nothing yet. */
        val pollWhileSubscribedMs: Long? = null,
    ) : NudgeRecovery
}
