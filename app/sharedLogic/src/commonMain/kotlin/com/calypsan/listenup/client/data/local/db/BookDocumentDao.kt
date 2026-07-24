package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [BookDocumentEntity] — per-book supplementary documents.
 *
 * Mirrors [AudioFileDao]: rows are always returned ordered by `index` ASC so callers don't
 * sort, and the backtick-quoted `` `index` `` escapes the SQLite keyword. The sync handler
 * uses the delete-then-upsert pattern inside its existing transaction; the DAO owns no
 * transactions. [observeForBook] is the reactive read the document repository observes.
 */
@Dao
internal interface BookDocumentDao {
    @Query("SELECT * FROM book_documents WHERE bookId = :bookId ORDER BY `index` ASC")
    fun observeForBook(bookId: String): Flow<List<BookDocumentEntity>>

    @Query("SELECT * FROM book_documents WHERE bookId = :bookId ORDER BY `index` ASC")
    suspend fun getForBook(bookId: String): List<BookDocumentEntity>

    @Upsert
    suspend fun upsertAll(entities: List<BookDocumentEntity>)

    @Query("DELETE FROM book_documents WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    /** Delete every document row. Used by the sign-out / server-switch library reset. */
    @Query("DELETE FROM book_documents")
    suspend fun deleteAll()
}
