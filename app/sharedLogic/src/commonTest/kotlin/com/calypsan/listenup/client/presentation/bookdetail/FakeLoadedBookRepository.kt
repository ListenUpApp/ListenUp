package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.domain.repository.LoadedBookRepository
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** The player's loaded book for Book Detail specs: [loadedBookId] is the whole of it; nothing is loaded by default. */
internal class FakeLoadedBookRepository(
    val loadedBookId: MutableStateFlow<BookId?> = MutableStateFlow(null),
) : LoadedBookRepository {
    override fun observe(): Flow<LoadedBook> = emptyFlow()

    override fun observeLoadedBookId(): Flow<BookId?> = loadedBookId

    override suspend fun loadedBookId(): BookId? = loadedBookId.value

    override suspend fun setLoaded(bookId: BookId) {
        loadedBookId.value = bookId
    }

    override suspend fun clear() {
        loadedBookId.value = null
    }
}
