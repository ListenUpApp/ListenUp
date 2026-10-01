package com.calypsan.listenup.server.hardcover

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What push ([HardcoverPushWorker]) and pull ([HardcoverPullWorker]) share per user: one Hardcover
 * conversation at a time ([withUser]), and one throttle pause ([pause]). Hardcover's rate limits are
 * per user, so a 429 answered to either direction holds both. Correctness never depends on the order push and pull take
 * turns in — the pushed-read ledger is written before ListenUp finishes a read — this only keeps the
 * two from talking over each other.
 */
class HardcoverUserGate {
    private val lock = SynchronizedObject()
    private val mutexes = HashMap<String, Mutex>()
    private val pausedUntil = HashMap<String, Long>()

    /** Runs [block] as [userId]'s only Hardcover conversation. Not reentrant. */
    suspend fun <T> withUser(
        userId: String,
        block: suspend () -> T,
    ): T = mutexFor(userId).withLock { block() }

    /** Holds [userId]'s Hardcover traffic until [untilMs] (epoch ms). A pause only ever moves later. */
    fun pause(
        userId: String,
        untilMs: Long,
    ) {
        synchronized(lock) { pausedUntil[userId] = maxOf(untilMs, pausedUntil[userId] ?: Long.MIN_VALUE) }
    }

    /** When [userId]'s pause ends (epoch ms), or null when they were never paused. */
    fun pausedUntil(userId: String): Long? = synchronized(lock) { pausedUntil[userId] }

    private fun mutexFor(userId: String): Mutex = synchronized(lock) { mutexes.getOrPut(userId) { Mutex() } }
}
