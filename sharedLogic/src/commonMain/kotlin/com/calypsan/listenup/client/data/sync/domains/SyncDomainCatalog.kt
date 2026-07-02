package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry

/**
 * The explicit list of every declared sync domain — the client's complete
 * sync rulebook in one value. Grows as Phase 2 migrates the remaining hand-written
 * handlers; when the migration completes, this list and the server's registrations
 * are asserted 1:1 by the completeness spec.
 */
internal class SyncDomainCatalog(
    val mirrored: List<MirroredDomain<*>>,
)

/**
 * Compiles every catalog entry into its runtime handler at app start — the
 * registry-population step that replaces per-handler `createdAtStart` singles.
 */
internal class ComposedHandlerRegistrar(
    private val catalog: SyncDomainCatalog,
    private val transactionRunner: TransactionRunner,
    private val registry: ClientSyncDomainRegistry,
) {
    /** Construct (and thereby self-register) a handler for every declared domain. */
    fun registerAll() {
        catalog.mirrored.forEach { it.toHandler(transactionRunner, registry) }
    }
}
