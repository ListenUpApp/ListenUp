package com.calypsan.listenup.server.librarywrite

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.io.files.Path

/**
 * Registry of paths the server itself is about to touch inside library folders.
 *
 * The file watcher consults this at raw-event intake: a registered, unexpired path means
 * "this event is our own write — swallow it". A path may receive several kernel events per
 * write (create + modify + rename); claims are therefore TTL-scoped, and [consumeIfSelfWrite]
 * exists for the terminal check where one-shot semantics matter. Thread-safe via a plain lock
 * (matches [com.calypsan.listenup.server.sync.SyncRegistry] and [com.calypsan.listenup.server.scanner.watcher.FolderWatcher]'s
 * own shared-state pattern — a `SynchronizedObject` critical section, not a suspending `Mutex`,
 * since claim bookkeeping is a bounded in-memory map op that never itself suspends). Clock
 * injected for tests. Deliberately in-memory: on restart, the persisted content-hash state
 * (Phase 2) catches anything the registry forgot.
 */
class SelfWriteRegistry(
    private val clock: () -> Long,
) {
    private val lock = SynchronizedObject()
    private val claims = mutableMapOf<String, Long>() // canonical path string → expiry epoch-ms

    /** Claims [path] as a self-write for the next [ttlMs] milliseconds. */
    fun register(
        path: Path,
        ttlMs: Long,
    ) = synchronized(lock) {
        claims[path.toString()] = clock() + ttlMs
    }

    /** True if [path] is currently claimed (registered and not yet expired). Does not consume the claim. */
    fun isSelfWrite(path: Path): Boolean =
        synchronized(lock) {
            val expiry = claims[path.toString()] ?: return@synchronized false
            if (clock() > expiry) {
                claims.remove(path.toString())
                false
            } else {
                true
            }
        }

    /**
     * Atomically checks and removes the claim for [path], returning whether it was
     * self-write. Use this at the terminal watcher check so a second, human-authored
     * event for the same path is never mistaken for our own write.
     */
    fun consumeIfSelfWrite(path: Path): Boolean =
        synchronized(lock) {
            val expiry = claims.remove(path.toString()) ?: return@synchronized false
            clock() <= expiry
        }

    /** Clears a claim early — e.g. the write failed, so no matching filesystem event will ever arrive. */
    fun release(path: Path) =
        synchronized(lock) {
            claims.remove(path.toString())
            Unit
        }
}
