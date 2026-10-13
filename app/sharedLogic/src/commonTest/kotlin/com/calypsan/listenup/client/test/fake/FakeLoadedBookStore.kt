package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.repository.LoadedBookStore

/**
 * In-memory [LoadedBookStore]. [clearSuspension] runs inside each save of null, so a test can hold Close's key
 * clear back until after the next load (a slow storage write). [reads] counts storage reads (the "Restoring resolves locally, once" check);
 * [readSuspension] runs inside each read, so a test can hold the first read open (a slow cold-start read);
 * [readFailure], when set, is thrown by every read (a keystore that will not open). [migrated] is the
 * once-only seed flag; it defaults to set, the steady state after the upgrade, so only the upgrade's own
 * specs see a seed.
 */
class FakeLoadedBookStore(
    var stored: String? = null,
    var migrated: Boolean = true,
) : LoadedBookStore {
    var reads = 0
    var readSuspension: suspend () -> Unit = {}
    var readFailure: Exception? = null

    override suspend fun readLoadedBookId(): String? {
        reads++
        readSuspension()
        readFailure?.let { throw it }
        return stored
    }

    var clearSuspension: suspend () -> Unit = {}

    override suspend fun saveLoadedBookId(bookId: String?) {
        if (bookId == null) clearSuspension()
        stored = bookId
    }

    override suspend fun readLoadedBookMigrated(): Boolean = migrated

    override suspend fun saveLoadedBookMigrated() {
        migrated = true
    }
}
