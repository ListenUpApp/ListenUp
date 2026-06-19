package com.calypsan.listenup.server.sync

import java.util.concurrent.ConcurrentHashMap

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
 * injection. Lookups happen in the SSE catch-up routes (REST `?since=`)
 * and the domain-discovery endpoint.
 */
class SyncRegistry {
    private val byDomain = ConcurrentHashMap<String, SyncableRepo<*>>()

    fun register(repo: SyncableRepo<*>) {
        val existing = byDomain.putIfAbsent(repo.domainName, repo)
        if (existing != null) {
            error(
                "domainName '${repo.domainName}' already registered with " +
                    "${existing::class.simpleName}; registering ${repo::class.simpleName} " +
                    "would overwrite (per-Koin registry should be 1:1)",
            )
        }
    }

    fun lookup(domainName: String): SyncableRepo<*>? = byDomain[domainName]

    fun knownDomains(): List<String> = byDomain.keys.sorted()
}
