package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * DAO for [AudioFileEntity] — per-book audio files used by playback and downloads.
 *
 * Queries always return rows ordered by `index` ASC so callers don't have to sort.
 * The backtick-quoted `` `index` `` in raw SQL is defensive — `INDEX` is used as a
 * DDL keyword in SQLite in some contexts, so escaping the column reference avoids
 * any parser ambiguity.
 *
 * Sync / SSE / playback-fallback writers all use the delete-then-upsert pattern
 * inside their existing `transactionRunner.atomically { }` blocks. The DAO does
 * not own transactions itself.
 */
@Dao
internal interface AudioFileDao {
    @Query("SELECT * FROM audio_files WHERE bookId = :bookId ORDER BY `index` ASC")
    suspend fun getForBook(bookId: String): List<AudioFileEntity>

    @Upsert
    suspend fun upsertAll(entities: List<AudioFileEntity>)

    @Query("DELETE FROM audio_files WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    /** Delete every audio file row. Used by the sign-out / server-switch library reset. */
    @Query("DELETE FROM audio_files")
    suspend fun deleteAll()
}
