package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.repository.LoadedBookStore

/**
 * In-memory [LoadedBookStore]. [reads] counts storage reads (the "Restoring resolves locally, once" check);
 * [readSuspension] runs inside each read, so a test can hold the first read open (a slow cold-start read).
 */
class FakeLoadedBookStore(
    var stored: String? = null,
) : LoadedBookStore {
    var reads = 0
    var readSuspension: suspend () -> Unit = {}

    override suspend fun readLoadedBookId(): String? {
        reads++
        readSuspension()
        return stored
    }

    override suspend fun saveLoadedBookId(bookId: String?) {
        stored = bookId
    }
}
