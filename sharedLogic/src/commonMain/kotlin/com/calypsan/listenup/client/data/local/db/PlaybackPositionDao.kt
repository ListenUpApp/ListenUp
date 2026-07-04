package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [PlaybackPositionEntity] operations.
 *
 * Provides local-first position persistence for instant resume.
 * Position is sacred: saves immediately, syncs eventually.
 */
@Dao
internal interface PlaybackPositionDao {
    /**
     * Get the saved position for a book.
     *
     * @param bookId The book to get position for
     * @return The position entity or null if never played
     */
    @Query("SELECT * FROM playback_positions WHERE bookId = :bookId")
    suspend fun get(bookId: BookId): PlaybackPositionEntity?

    /**
     * Observe the saved position for a book.
     *
     * @param bookId The book to observe
     * @return Flow emitting position updates
     */
    @Query("SELECT * FROM playback_positions WHERE bookId = :bookId")
    fun observe(bookId: BookId): Flow<PlaybackPositionEntity?>

    /**
     * Save or update position. Instant, local operation.
     *
     * @param position The position to save
     */
    @Upsert
    suspend fun save(position: PlaybackPositionEntity)

    /**
     * Save or update multiple positions in a single transaction.
     *
     * @param positions The positions to save
     */
    @Upsert
    suspend fun saveAll(positions: List<PlaybackPositionEntity>)

    /**
     * Get positions for multiple books.
     * Used for batch operations during sync.
     *
     * @param bookIds The book IDs to get positions for
     * @return List of positions (may be fewer than requested if some don't exist)
     */
    @Query("SELECT * FROM playback_positions WHERE bookId IN (:bookIds)")
    suspend fun getByBookIds(bookIds: List<BookId>): List<PlaybackPositionEntity>

    /**
     * Update only the playback position and timestamps for an existing record.
     *
     * IMPORTANT: This intentionally does NOT touch [PlaybackPositionEntity.hasCustomSpeed]
     * or [PlaybackPositionEntity.playbackSpeed]. This prevents a read-modify-write race
     * between periodic saves (savePosition) and explicit speed changes (onSpeedChanged).
     * Both run on Dispatchers.IO concurrently and would otherwise clobber each other.
     *
     * @return The number of rows updated (0 if no record exists for this book)
     */
    @Query(
        "UPDATE playback_positions SET positionMs = :positionMs, updatedAt = :updatedAt, " +
            "syncedAt = NULL, lastPlayedAt = :lastPlayedAt WHERE bookId = :bookId",
    )
    suspend fun updatePositionOnly(
        bookId: BookId,
        positionMs: Long,
        updatedAt: Long,
        lastPlayedAt: Long,
    ): Int

    /**
     * Delete position for a book.
     * Used when resetting progress.
     *
     * @param bookId The book to clear position for
     */
    @Query("DELETE FROM playback_positions WHERE bookId = :bookId")
    suspend fun delete(bookId: BookId)

    /**
     * Delete all positions.
     * Used for testing and account logout.
     */
    @Query("DELETE FROM playback_positions")
    suspend fun deleteAll()

    /** Apply a server tombstone: set the soft-delete timestamp and revision. */
    @Query(
        "UPDATE playback_positions SET deletedAt = :deletedAt, revision = :revision WHERE bookId = :id",
    )
    suspend fun softDelete(
        id: BookId,
        deletedAt: Long,
        revision: Long,
    )

    /**
     * Drop every position whose book is gone or tombstoned — the Continue-Listening
     * counterpart to the readership sweep, run from the books access-gate `afterPrune`.
     *
     * When a book leaves the local mirror (a real removal tombstone, or an access-loss
     * prune), its lingering position would otherwise keep the book on the Home
     * "Continue Listening" shelf and re-surface a book the user can no longer reach.
     * The server still holds the row for an access-only loss, so a later re-grant
     * re-syncs the position — this is a local hard-delete of a now-unreachable cache
     * entry, not a destructive server write.
     */
    @Query("DELETE FROM playback_positions WHERE bookId NOT IN (SELECT id FROM books WHERE deletedAt IS NULL)")
    suspend fun deleteWhereBookNotLive()

    /**
     * Get recently played books for "Continue Listening" section.
     * Returns positions ordered by most recently played, limited to specified count.
     *
     * Uses COALESCE to handle legacy data where lastPlayedAt may be null,
     * falling back to updatedAt in those cases.
     *
     * @param limit Maximum number of positions to return
     * @return List of positions ordered by lastPlayedAt descending (with updatedAt fallback)
     */
    @Query("SELECT * FROM playback_positions ORDER BY COALESCE(lastPlayedAt, updatedAt) DESC LIMIT :limit")
    suspend fun getRecentPositions(limit: Int): List<PlaybackPositionEntity>

    /**
     * Reactive counterpart to [getRecentPositions] — emits the [limit] most recently
     * started, unfinished positions whenever any position row changes, pushing the
     * sort and the limit to SQL so Home's Continue Listening shelf never has to pull
     * every position to the client just to take the top N.
     *
     * Excludes positions with `positionMs = 0` (unstarted) AND `isFinished = true`
     * (already done) — the client-side duration-based filter handles the in-flight
     * case where positionMs has been updated but isFinished hasn't yet been
     * recomputed. SQL-filtering isFinished eliminates the limit-then-filter undercount
     * that previously caused the shelf to appear shorter than it should be.
     *
     * @param limit Maximum number of positions to emit per update
     * @return Flow emitting ordered positions; re-emits on any row change
     */
    @Query(
        "SELECT * FROM playback_positions " +
            "WHERE positionMs > 0 AND isFinished = 0 " +
            "ORDER BY COALESCE(lastPlayedAt, updatedAt) DESC LIMIT :limit",
    )
    fun observeRecentPositions(limit: Int): Flow<List<PlaybackPositionEntity>>

    /**
     * Observe all playback positions.
     * Used for displaying progress indicators throughout the app.
     *
     * @return Flow emitting list of all positions whenever any position changes
     */
    @Query("SELECT * FROM playback_positions")
    fun observeAll(): Flow<List<PlaybackPositionEntity>>
}
