package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow

/**
 * This device's loaded book (spec §4.4, F1, S2). Survives restarts; cleared only by Close book, sign-out or
 * the book's row disappearing. Written by `PlayerSession` alone; read by Home and the system resume entries.
 */
interface LoadedBookRepository {
    /** The loaded book for non-player screens. `Restoring` first, then a value per real change. */
    fun observe(): Flow<LoadedBook>

    /** The persisted id, emitted only after the local read (never a pre-read null). */
    fun observeLoadedBookId(): Flow<BookId?>

    /** The persisted id, reading storage if it has not been read yet. Works with no UI (Media3 resumption). */
    suspend fun loadedBookId(): BookId?

    /** Make [bookId] the loaded book. */
    suspend fun setLoaded(bookId: BookId)

    /** Nothing is loaded. */
    suspend fun clear()
}
