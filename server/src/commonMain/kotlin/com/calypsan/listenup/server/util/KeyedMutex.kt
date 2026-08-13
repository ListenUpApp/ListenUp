package com.calypsan.listenup.server.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-key suspend serialization: [withLock] runs [block] holding a [Mutex] dedicated to [key],
 * so critical sections for the same key never interleave while different keys proceed in
 * parallel. Entries are never evicted, so a caller's key space must be bounded by something small
 * at the self-hosted scale this server targets — user ids, or a cover derivative's hash and width.
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
