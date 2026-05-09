package com.calypsan.listenup.server.sync

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-domain registry populated by [SyncableRepository] init blocks.
 * REST + digest + domain-list routes look up repositories by name here.
 *
 * This is the static-registry style used in the RPC Exception Guard's
 * `RpcGuard.dispatch()` — small startup wart, but explicit: every
 * repository announces itself.
 */
internal object SyncRoutes {
    private val registry = ConcurrentHashMap<String, SyncableRepository<*, *>>()

    fun register(
        domainName: String,
        repository: SyncableRepository<*, *>,
    ) {
        registry[domainName] = repository
    }

    fun lookup(domainName: String): SyncableRepository<*, *>? = registry[domainName]

    fun knownDomains(): List<String> = registry.keys.sorted()
}
