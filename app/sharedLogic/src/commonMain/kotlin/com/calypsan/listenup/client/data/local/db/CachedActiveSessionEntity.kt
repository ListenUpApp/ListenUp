package com.calypsan.listenup.client.data.local.db

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room mirror of the "what others are listening to" roster — the last server-fetched
 * `currentlyListening` snapshot, one row per other user. Persisted so the presence surface renders
 * (possibly stale) offline or on a transient RPC failure instead of blanking; [observedAt] records when
 * the snapshot was taken so the UI can flag staleness (presence is time-sensitive). Book identity is
 * joined from the local library at read time ([CachedActiveSessionDao.observeWithBooks]), so only the
 * wire fields are stored here. Refreshed
 * wholesale on each presence ping while online; never cleared on failure.
 *
 * A row is either live ([isLive]) or a recent-listen fill; [lastActiveAtMs] carries the one timestamp
 * either kind has, so no column is meaningless for half the rows.
 *
 * @property userId The other user this row is about — one row per user, so it is the key.
 * @property displayName Their public display name at snapshot time.
 * @property avatarType `"auto"` or `"image"`.
 * @property bookId The book to show for them.
 * @property lastActiveAtMs Epoch-ms they were last active on [bookId] — the live session's start
 *   when [isLive], otherwise the position's `lastPlayedAt`.
 * @property isLive True when they were listening at snapshot time.
 * @property observedAt Epoch-ms the snapshot was taken, for the staleness affordance.
 */
@Entity(tableName = "cached_active_sessions")
internal data class CachedActiveSessionEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val avatarType: String,
    val bookId: String,
    val lastActiveAtMs: Long,
    val isLive: Boolean,
    val observedAt: Long,
)

@Dao
internal interface CachedActiveSessionDao {
    /**
     * Every cached row whose book is in the local library, with the book fields the roster shows —
     * one query instead of a book read per row on every emission. Being a join, it also re-emits
     * when the library changes, so a session whose book syncs in after the snapshot appears then
     * rather than at the next presence ping. Live rows first, newest activity first within each half.
     */
    @Query(
        """
        SELECT s.*, b.title AS bookTitle, b.coverHash AS bookCoverHash,
            (
                SELECT c.name FROM book_contributors bc
                INNER JOIN contributors c ON bc.contributorId = c.id
                WHERE bc.bookId = b.id AND bc.role = 'author'
                LIMIT 1
            ) AS bookAuthorName
        FROM cached_active_sessions s
        INNER JOIN books b ON b.id = s.bookId AND b.deletedAt IS NULL
        ORDER BY s.isLive DESC, s.lastActiveAtMs DESC
        """,
    )
    fun observeWithBooks(): Flow<List<CachedActiveSessionWithBook>>

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

/**
 * A cached presence row joined with the book it names — the read shape of
 * [CachedActiveSessionDao.observeWithBooks].
 */
internal data class CachedActiveSessionWithBook(
    @Embedded val session: CachedActiveSessionEntity,
    val bookTitle: String,
    val bookCoverHash: String?,
    val bookAuthorName: String?,
)
