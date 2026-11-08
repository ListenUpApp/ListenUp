package com.calypsan.listenup.server.services

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises the two operations that decide where a book lives: [BookPersister] turning a scan
 * into database rows, and the organizer moving a book's files and its `root_rel_path`
 * ([com.calypsan.listenup.server.organize.MoveManifestExecutor], which both the organize run and
 * the edit relocator go through).
 *
 * A scan walks the disk well before it persists. A book the organizer moves in between is seen at
 * its old folder by the walk and recorded at its new one by the database. The persister therefore
 * checks the walk against the disk before it creates or tombstones anything, and this lock is what
 * makes that check hold: while the persister has it, no move can land between the check and the
 * write.
 *
 * One instance per server, shared by both sides through dependency injection. Not reentrant: code
 * holding it must not call back into the other side.
 */
class LibraryLayoutLock {
    private val mutex = Mutex()

    /** Runs [block] while no other holder can change the library's layout. */
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
