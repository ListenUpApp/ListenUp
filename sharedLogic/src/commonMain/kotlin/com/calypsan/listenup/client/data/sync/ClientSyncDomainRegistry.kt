package com.calypsan.listenup.client.data.sync

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Per-process registry of [SyncDomainHandler]s, populated at app start by each
 * handler's `init` block. The engine looks handlers up by name as firehose events
 * and REST catch-up pages arrive.
 *
 * Mirror of the server's `SyncRoutes` registry shape — same self-registration
 * model, same lookup-by-name discipline.
 *
 * Thread-safe: `register` is called from Koin singleton creation (potentially
 * concurrent), `lookup` is called from the dispatcher's coroutine.
 */
internal class ClientSyncDomainRegistry : SynchronizedObject() {
    private val handlers = mutableMapOf<String, SyncDomainHandler<*>>()

    /**
     * Register [handler] under its [SyncDomainHandler.domainName]. Idempotent for
     * the same instance — re-registration of the same handler is a no-op.
     * Registering a different instance for an existing domain throws
     * [IllegalStateException] (programmer error: two handlers for one domain).
     */
    fun register(handler: SyncDomainHandler<*>) {
        synchronized(this) {
            val existing = handlers[handler.domainName]
            if (existing != null && existing !== handler) {
                error(
                    "Two handlers registered for domain '${handler.domainName}': " +
                        "${existing::class.simpleName} and ${handler::class.simpleName}",
                )
            }
            handlers[handler.domainName] = handler
        }
    }

    /** Look up a handler by [domainName], or null if no handler is registered for it. */
    fun lookup(domainName: String): SyncDomainHandler<*>? =
        synchronized(this) {
            handlers[domainName]
        }

    /** All registered domain names, sorted alphabetically for stable iteration. */
    fun registeredDomains(): List<String> =
        synchronized(this) {
            handlers.keys.sorted()
        }

    /**
     * The registered handlers whose domain is access-gated (those implementing
     * [AccessFilteredSyncHandler]), in stable domain-name order. The `AccessChanged`
     * reconcile iterates these to re-derive and prune the caller's accessible set.
     */
    fun accessFilteredHandlers(): List<SyncDomainHandler<*>> =
        synchronized(this) {
            handlers.entries
                .sortedBy { it.key }
                .map { it.value }
                .filterIsInstance<AccessFilteredSyncHandler>()
                .map { it as SyncDomainHandler<*> }
        }
}
