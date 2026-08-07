package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncDomains
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

    /**
     * All registered domain names in catch-up order: [CATCH_UP_PRIORITY] first, then everything
     * else alphabetically. Deterministic either way, so iteration stays stable.
     *
     * Order here is latency, not cosmetics. [SyncCatchUpClient.catchUpAll] walks this list
     * **strictly sequentially**, one full round-trip per domain, so a domain's position is how
     * long it waits to become correct.
     */
    fun registeredDomains(): List<String> =
        synchronized(this) {
            val prioritised = CATCH_UP_PRIORITY.filter { it in handlers }
            prioritised + (handlers.keys - prioritised.toSet()).sorted()
        }

    private companion object {
        /**
         * Domains that gate a correctness decision and must not queue behind decoration.
         *
         * `playback_positions` decides **where a book resumes**. Plain alphabetical order put it
         * ~14th of 22, behind `activities`, `admin_user_roster`, `book_moods`, `book_tags` and the
         * rest — purely because "p" sorts late. Until it lands, opening a book on a second device
         * resolves against a stale local row, which is exactly the race the resume reconcile has
         * to defend against; catching it up first means the reconcile usually has nothing to
         * correct.
         *
         * Keep this list short. A domain belongs here only if being stale makes the app *wrong*,
         * not merely out of date.
         */
        private val CATCH_UP_PRIORITY = listOf(SyncDomains.PLAYBACK_POSITIONS.name)
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
