package com.calypsan.listenup.server.hardcover

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val CHANGE_BUFFER = 64

/**
 * What a user's "Sync now" is doing, held in memory, and the signal that a user's connection state
 * is worth republishing ([changes]).
 *
 * Each request bumps that user's generation. A pull serves the generation that was current when its
 * step began ([generation]), so a page already in flight when the user pressed "Sync now" can't end
 * the sync they just asked for: only a pull that began after the request settles it, as caught up
 * ([pullCaughtUp]) or failed ([pullFailed]). Any later pull that catches up clears a failure.
 *
 * Nothing here is persisted. After a restart nothing is in flight, so there is nothing to remember.
 */
class HardcoverSyncActivity {
    private val lock = SynchronizedObject()
    private val generations = HashMap<String, Long>()
    private val awaiting = HashMap<String, Long>()
    private val failed = HashSet<String>()
    private val changed =
        MutableSharedFlow<String>(extraBufferCapacity = CHANGE_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The id of each user whose connection state may have changed, as it changes. */
    val changes: SharedFlow<String> = changed.asSharedFlow()

    /** [userId] pressed "Sync now": they are syncing until a pull that begins after this ends. */
    fun syncRequested(userId: String) {
        synchronized(lock) {
            val next = (generations[userId] ?: 0L) + 1
            generations[userId] = next
            awaiting[userId] = next
            failed.remove(userId)
        }
        changed.tryEmit(userId)
    }

    /** The request generation a pull beginning now serves. */
    fun generation(userId: String): Long = synchronized(lock) { generations[userId] ?: 0L }

    /** A pull that began at generation [served] caught up: it settles a request it served, and clears any failure. */
    fun pullCaughtUp(
        userId: String,
        served: Long,
    ) {
        synchronized(lock) {
            failed.remove(userId)
            if (isServedBy(userId, served)) awaiting.remove(userId)
        }
        changed.tryEmit(userId)
    }

    /** A pull that began at generation [served] failed: a request it served ends, as failed. */
    fun pullFailed(
        userId: String,
        served: Long,
    ) {
        val settled =
            synchronized(lock) {
                isServedBy(userId, served).also { settles ->
                    if (settles) {
                        awaiting.remove(userId)
                        failed.add(userId)
                    }
                }
            }
        if (settled) changed.tryEmit(userId)
    }

    /** [userId]'s stored sync health moved (a push landed, a pull caught up, an error passed its cap). */
    fun healthChanged(userId: String) {
        changed.tryEmit(userId)
    }

    /** One of [userId]'s Hardcover choices changed (the share mode): their Connected is worth republishing. */
    fun preferencesChanged(userId: String) {
        changed.tryEmit(userId)
    }

    /** [userId]'s connection ended or broke: nothing is in flight for them any more. */
    fun forget(userId: String) {
        synchronized(lock) {
            awaiting.remove(userId)
            failed.remove(userId)
        }
        changed.tryEmit(userId)
    }

    /** Whether a "Sync now" [userId] asked for is still running. */
    fun isSyncing(userId: String): Boolean = synchronized(lock) { userId in awaiting }

    /** Whether the last "Sync now" [userId] asked for failed, and nothing has caught up since. */
    fun syncNowFailed(userId: String): Boolean = synchronized(lock) { userId in failed }

    private fun isServedBy(
        userId: String,
        served: Long,
    ): Boolean = awaiting[userId]?.let { it <= served } == true
}
