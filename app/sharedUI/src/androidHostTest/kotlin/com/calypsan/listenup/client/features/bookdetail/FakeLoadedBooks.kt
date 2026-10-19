package com.calypsan.listenup.client.features.bookdetail

import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.domain.repository.LoadedBookRepository
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** The player's loaded book for Book Detail screen tests: [loaded], nothing by default. */
internal class FakeLoadedBooks(
    val loaded: MutableStateFlow<LoadedBook> = MutableStateFlow(LoadedBook.NothingLoaded),
) : LoadedBookRepository {
    override fun observe(): Flow<LoadedBook> = loaded

    override fun observeLoadedBookId(): Flow<BookId?> =
        loaded.map {
            (it as? LoadedBook.Loaded)?.let { book -> BookId(book.bookId) }
        }

    override suspend fun loadedBookId(): BookId? = (loaded.value as? LoadedBook.Loaded)?.let { BookId(it.bookId) }

    override suspend fun setLoaded(bookId: BookId) {
        loaded.value = LoadedBook.Loaded(bookId.value, isPlaying = false, isFinished = false)
    }

    override suspend fun clear() {
        loaded.value = LoadedBook.NothingLoaded
    }
}
