package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room mirror of a book's readership — the last server-fetched `bookReadership` snapshot for one book,
 * one row per reader. Persisted so the Book Detail "readers" section renders (possibly stale) offline
 * or on a transient RPC failure, instead of blanking. Refreshed wholesale per book on each presence
 * ping while online; never cleared on failure. [observedAt] records when the snapshot was taken so the
 * UI can show a staleness affordance.
 *
 * `finishesJson` holds the reader's finish timestamps (epoch-ms, newest-first) as a JSON array — the
 * repository (de)serializes it; keeping it a scalar column avoids a normalized child table for a small,
 * always-replaced-together list.
 */
@Entity(tableName = "book_readership", primaryKeys = ["bookId", "userId"])
internal data class BookReadershipEntity(
    val bookId: String,
    val userId: String,
    val displayName: String,
    val avatarType: String,
    val currentProgressPct: Int?,
    val finishesJson: String,
    val observedAt: Long,
)

@Dao
internal interface BookReadershipDao {
    /** Readers of [bookId], in-progress first (by descending progress), then finished-only readers. */
    @Query(
        "SELECT * FROM book_readership WHERE bookId = :bookId " +
            "ORDER BY currentProgressPct IS NULL, currentProgressPct DESC",
    )
    fun observeForBook(bookId: String): Flow<List<BookReadershipEntity>>

    @Upsert
    suspend fun upsertAll(rows: List<BookReadershipEntity>)

    @Query("DELETE FROM book_readership WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    /**
     * Sweep cached readership whose book is gone or tombstoned. The mirror is a
     * server-fetched cache (refilled on the next presence ping), so rows for
     * dead or revoked books carry reader identities the user should no longer
     * hold — and nothing else references them.
     */
    @Query("DELETE FROM book_readership WHERE bookId NOT IN (SELECT id FROM books WHERE deletedAt IS NULL)")
    suspend fun deleteWhereBookNotLive()

    /** Delete every cached readership row. Used by the sign-out / server-switch library reset. */
    @Query("DELETE FROM book_readership")
    suspend fun deleteAll()

    /** Atomically replace [bookId]'s cached readership with [rows] (the latest server snapshot). */
    @Transaction
    suspend fun replaceForBook(
        bookId: String,
        rows: List<BookReadershipEntity>,
    ) {
        deleteForBook(bookId)
        upsertAll(rows)
    }
}
