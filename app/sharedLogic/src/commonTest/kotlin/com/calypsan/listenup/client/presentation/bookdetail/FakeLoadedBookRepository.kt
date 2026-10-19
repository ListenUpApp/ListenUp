package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.domain.repository.LoadedBookRepository
import com.calypsan.listenup.client.playback.BookPlayhead
import com.calypsan.listenup.client.playback.LoadedBookPlayhead
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The player's loaded book for Book Detail specs: [loadedBookId] for Book Detail, [loaded] (whether it is playing) for
 * Readers. Nothing is loaded by default.
 */
internal class FakeLoadedBookRepository(
    val loadedBookId: MutableStateFlow<BookId?> = MutableStateFlow(null),
    val loaded: MutableStateFlow<LoadedBook> = MutableStateFlow(LoadedBook.NothingLoaded),
) : LoadedBookRepository {
    override fun observe(): Flow<LoadedBook> = loaded

    override fun observeLoadedBookId(): Flow<BookId?> = loadedBookId

    override suspend fun loadedBookId(): BookId? = loadedBookId.value

    override suspend fun setLoaded(bookId: BookId) {
        loadedBookId.value = bookId
    }

    override suspend fun clear() {
        loadedBookId.value = null
    }
}

/** The player's playhead for Book Detail specs that do not move it: nothing playing. */
internal class FakeLoadedBookPlayhead(
    override val playhead: MutableStateFlow<BookPlayhead?> = MutableStateFlow(null),
) : LoadedBookPlayhead
