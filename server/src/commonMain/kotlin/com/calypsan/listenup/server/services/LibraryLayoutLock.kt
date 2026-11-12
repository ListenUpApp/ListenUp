package com.calypsan.listenup.server.services

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises the operations that decide where a book lives, or whether it lives at all:
 * [BookPersister] turning a scan into database rows, the organizer moving a book's files and its
 * `root_rel_path` ([com.calypsan.listenup.server.organize.MoveManifestExecutor], which both the
 * organize run and the edit relocator go through), and [BookDeleter] removing a book's folder and
 * tombstoning its row.
 *
 * A scan walks the disk well before it persists. A book the organizer moves or an admin deletes in
 * between is seen by the walk where it no longer is. The persister therefore checks the walk against
 * the disk before it creates or tombstones anything, and this lock is what makes that check hold:
 * while the persister has it, no move or delete can land between the check and the write.
 *
 * One instance per server, shared by every holder through dependency injection. Not reentrant: code
 * holding it must not call back into another holder.
 */
class LibraryLayoutLock {
    private val mutex = Mutex()

    /** Runs [block] while no other holder can change the library's layout. */
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
