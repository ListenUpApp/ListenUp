package com.calypsan.listenup.client.data.local.db

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room mirror of the "who's listening now" presence roster — the last server-fetched
 * `currentlyListening` snapshot, one row per listening user. Persisted so the presence surface renders
 * (possibly stale) offline or on a transient RPC failure instead of blanking; [observedAt] records when
 * the snapshot was taken so the UI can flag staleness (presence is time-sensitive). Book identity is
 * enriched from the local library at read time, so only the wire fields are stored here. Refreshed
 * wholesale on each presence ping while online; never cleared on failure.
 */
@Entity(tableName = "cached_active_sessions")
internal data class CachedActiveSessionEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val avatarType: String,
    val bookId: String,
    val startedAtMs: Long,
    val observedAt: Long,
)

@Dao
internal interface CachedActiveSessionDao {
    @Query("SELECT * FROM cached_active_sessions ORDER BY startedAtMs DESC")
    fun observeAll(): Flow<List<CachedActiveSessionEntity>>

    @Upsert
    suspend fun upsertAll(rows: List<CachedActiveSessionEntity>)

    @Query("DELETE FROM cached_active_sessions")
    suspend fun deleteAll()

    /** Atomically replace the cached presence roster with [rows] (the latest server snapshot). */
    @Transaction
    suspend fun replaceAll(rows: List<CachedActiveSessionEntity>) {
        deleteAll()
        upsertAll(rows)
    }
}
