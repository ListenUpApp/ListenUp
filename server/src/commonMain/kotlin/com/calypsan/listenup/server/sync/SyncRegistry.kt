package com.calypsan.listenup.server.sync

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Per-Koin registry of syncable repositories, keyed by `domainName`.
 *
 * Replaces the prior `internal object SyncRoutes` static singleton. Tests
 * that construct independent Koin containers now see independent registries
 * — no cross-container leakage when `kotest.parallelism > 1`. Production
 * uses exactly one Koin container, so the registry is effectively still a
 * singleton — just one scoped to the application lifecycle rather than the
 * JVM.
 *
 * Registration is performed by [SyncableRepository.init] via constructor
 * injection. Lookups happen in the sync catch-up routes (REST `?since=`)
 * and the domain-discovery endpoint.
 */
class SyncRegistry {
    private val lock = SynchronizedObject()
    private val byDomain = mutableMapOf<String, SyncableRepo<*>>()

    fun register(repo: SyncableRepo<*>) =
        synchronized(lock) {
            val existing = byDomain[repo.domainName]
            if (existing != null) {
                error(
                    "domainName '${repo.domainName}' already registered with " +
                        "${existing::class.simpleName}; registering ${repo::class.simpleName} " +
                        "would overwrite (per-Koin registry should be 1:1)",
                )
            }
            byDomain[repo.domainName] = repo
        }

    fun lookup(domainName: String): SyncableRepo<*>? = synchronized(lock) { byDomain[domainName] }

    fun knownDomains(): List<String> = synchronized(lock) { byDomain.keys.sorted() }
}
