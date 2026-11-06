package com.calypsan.listenup.server.media

import com.calypsan.listenup.server.util.KeyedMutex

/**
 * Serialises every avatar mutation for one user: the upload route's store-then-write, and a profile edit's write plus
 * its revert-to-auto delete. Without it a revert can delete bytes an upload is about to point the row at, leaving
 * `avatar_type = image` with no file.
 *
 * Not reentrant (see [KeyedMutex]): [withLock] for a user must never be entered from inside another [withLock] for
 * the same user.
 */
internal class AvatarWriteLock {
    private val mutex = KeyedMutex()

    /** Runs [block] while holding [userId]'s avatar lock. */
    suspend fun <T> withLock(
        userId: String,
        block: suspend () -> T,
    ): T = mutex.withLock(userId, block)
}
