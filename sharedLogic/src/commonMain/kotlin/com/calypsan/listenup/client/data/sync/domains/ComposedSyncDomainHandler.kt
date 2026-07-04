package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.SyncPayload
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.AccessFilteredSyncHandler
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.KSerializer

private val logger = KotlinLogging.logger {}

/**
 * The one [SyncDomainHandler] implementation: interprets a [MirroredDomain]
 * descriptor against the engine's existing runtime contract. Policy decisions
 * (conflict guards, delete routing, digest posture) live here, written once;
 * the descriptor's [MirrorApply] contains only the domain's Room mapping.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction, exactly like the
 * hand-written handlers it replaces.
 */
internal open class ComposedSyncDomainHandler<T : SyncPayload>(
    private val domain: MirroredDomain<T>,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<T> {
    override val domainName: String = domain.key.name
    override val payloadSerializer: KSerializer<T> = domain.key.serializer

    override fun syncId(item: T): String = domain.syncIdOf(item)

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<T>,
        isOwnEcho: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, event.id, logger) {
            if (isStale(event.id, event.revision)) {
                logger.debug { "[$domainName] skipping stale inbound rev=${event.revision} for ${event.id}" }
                return@applyEventAtomically
            }
            when (event) {
                is SyncEvent.Created -> {
                    applyGuarded(event.id, event.payload, isOwnEcho)
                }

                is SyncEvent.Updated -> {
                    applyGuarded(event.id, event.payload, isOwnEcho)
                }

                is SyncEvent.Deleted -> {
                    when (val deletes = domain.deletes) {
                        is DeleteSemantics.SoftDelete -> {
                            deletes.tombstoneById(event.id, event.occurredAt, event.revision)
                        }

                        is DeleteSemantics.CatchUpOnly -> {
                            logger.debug {
                                "[$domainName] Deleted for ${event.id} deferred to catch-up: ${deletes.reason}"
                            }
                        }
                    }
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: T,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, syncId(item), logger) {
            if (isStale(syncId(item), item.revision)) {
                logger.debug { "[$domainName] skipping stale catch-up item ${syncId(item)}" }
                return@applyEventAtomically
            }
            if (isTombstone) {
                domain.apply.tombstoneFromItem(item)
            } else {
                // Catch-up has no echo concept; apply through the conflict guard.
                applyConflictGuarded(item)
            }
        }

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>>? =
        when (val digest = domain.digest) {
            is DigestParticipation.Full -> digest.rows(maxRevision)
            is DigestParticipation.OptOut -> null
        }

    private suspend fun applyGuarded(
        id: String,
        payload: T,
        isOwnEcho: Boolean,
    ) {
        val conflict = domain.conflict
        if (isOwnEcho && conflict is ConflictPolicy.EchoShielded && conflict.onOwnEcho(id, payload)) return
        applyConflictGuarded(payload)
    }

    private suspend fun applyConflictGuarded(payload: T) {
        when (val conflict = domain.conflict) {
            is ConflictPolicy.ServerWins,
            is ConflictPolicy.AppendOnly,
            is ConflictPolicy.EchoShielded,
            -> {
                domain.apply.upsert(payload)
            }

            is ConflictPolicy.NewerWins -> {
                val existing = conflict.existingStamp(payload)
                if (existing != null && existing >= conflict.incomingStamp(payload)) return
                domain.apply.upsert(payload)
            }
        }
    }

    /** True when the local row's revision is strictly ahead of [incoming] — see [RevisionGuard]. */
    private suspend fun isStale(
        syncId: String,
        incoming: Long,
    ): Boolean {
        val guard = domain.conflict.revisionGuard ?: return false
        val local = guard.localRevision(syncId) ?: return false
        return local > incoming
    }
}

/**
 * The [RevisionGuard] a policy carries, or null for policies that don't compare
 * revisions ([ConflictPolicy.AppendOnly] is insert-if-absent; [ConflictPolicy.NewerWins]
 * carries its own timestamp guard).
 */
private val <T : Any> ConflictPolicy<T>.revisionGuard: RevisionGuard?
    get() =
        when (this) {
            is ConflictPolicy.ServerWins -> revisionGuard
            is ConflictPolicy.EchoShielded -> revisionGuard
            is ConflictPolicy.AppendOnly, is ConflictPolicy.NewerWins -> null
        }

/**
 * Access-gated variant: additionally implements [AccessFilteredSyncHandler] by
 * delegating to the descriptor's [AccessGate], so the registry's
 * `accessFilteredHandlers()` discovery keeps working unchanged.
 */
internal class AccessFilteredComposedSyncDomainHandler<T : SyncPayload>(
    domain: MirroredDomain<T>,
    private val gate: AccessGate,
    transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : ComposedSyncDomainHandler<T>(domain, transactionRunner, registry),
    AccessFilteredSyncHandler {
    override suspend fun localLiveIds(): Set<String> = gate.localLiveIds()

    override suspend fun pruneTo(
        accessibleIds: Set<String>,
        now: Long,
    ) = gate.pruneTo(accessibleIds, now)
}

/** Compile a descriptor into the runtime handler the engine speaks. */
internal fun <T : SyncPayload> MirroredDomain<T>.toHandler(
    transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
): SyncDomainHandler<T> =
    when (val gate = accessGate) {
        null -> ComposedSyncDomainHandler(this, transactionRunner, registry)
        else -> AccessFilteredComposedSyncDomainHandler(this, gate, transactionRunner, registry)
    }
