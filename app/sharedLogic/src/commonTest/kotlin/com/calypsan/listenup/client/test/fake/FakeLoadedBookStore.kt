package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.repository.LoadedBookStore

/** In-memory [LoadedBookStore]. [reads] counts storage reads (the "Restoring resolves locally, once" check). */
class FakeLoadedBookStore(
    var stored: String? = null,
) : LoadedBookStore {
    var reads = 0

    override suspend fun readLoadedBookId(): String? {
        reads++
        return stored
    }

    override suspend fun saveLoadedBookId(bookId: String?) {
        stored = bookId
    }
}
