package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.repository.LoadedBookStore

/**
 * In-memory [LoadedBookStore]. [reads] counts storage reads (the "Restoring resolves locally, once" check);
 * [readSuspension] runs inside each read, so a test can hold the first read open (a slow cold-start read);
 * [readFailure], when set, is thrown by every read (a keystore that will not open).
 */
class FakeLoadedBookStore(
    var stored: String? = null,
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

    override suspend fun saveLoadedBookId(bookId: String?) {
        stored = bookId
    }
}
