package com.calypsan.listenup.server.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-key suspend serialization: [withLock] runs [block] holding a [Mutex] dedicated to [key],
 * so critical sections for the same key never interleave while different keys proceed in
 * parallel. Keys here are user ids; entries are never evicted — the map is bounded by the user
 * count, which is small at the self-hosted scale this server targets.
 *
 * NOT reentrant: [Mutex] is not a reentrant lock, so [block] must never call [withLock] for the
 * same key (directly or transitively).
 */
class KeyedMutex {
    private val mapLock = Mutex()
    private val locks = HashMap<String, Mutex>()

    /** Runs [block] while holding the mutex for [key]. */
    suspend fun <T> withLock(
        key: String,
        block: suspend () -> T,
    ): T {
        val lock = mapLock.withLock { locks.getOrPut(key) { Mutex() } }
        return lock.withLock { block() }
    }
}
