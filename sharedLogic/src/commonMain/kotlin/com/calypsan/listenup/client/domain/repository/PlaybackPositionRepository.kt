@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import kotlinx.coroutines.flow.Flow

/**
 * Information about the last played book for resumption from system UI.
 * (Android Auto, Wear OS, system notifications, etc.)
 */
data class LastPlayedInfo(
    val bookId: BookId,
    val positionMs: Long,
    val playbackSpeed: Float,
)

/**
 * Repository contract for playback position operations.
 *
 * Provides access to saved playback positions for books.
 * Position is sacred data - never lose where the user left off.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface PlaybackPositionRepository {
    /**
     * Read the persisted playback position for [bookId] as a domain model. Returns
     * null inside [AppResult.Success] if the book has never been played; returns
     * [AppResult.Failure] if the DAO read fails.
     */
    suspend fun get(bookId: BookId): AppResult<PlaybackPosition?>

    /**
     * Observe all playback positions reactively.
     *
     * Used for displaying progress indicators across the library,
     * showing completion percentages on book cards.
     *
     * @return Flow emitting map of [BookId] to PlaybackPosition
     */
    fun observeAll(): Flow<Map<BookId, PlaybackPosition>>

    /**
     * Observe the persisted playback position for a single book. Emits null while
     * the book has never been played. Cheaper than [observeAll] when only one
     * book's position matters (e.g. the Now Playing liveness watcher).
     *
     * @param bookId The book to observe.
     * @return Flow emitting the book's position, or null when absent.
     */
    fun observe(bookId: BookId): Flow<PlaybackPosition?>

    /**
     * Delete position for a book.
     *
     * Used when resetting progress or removing a book.
     *
     * @param bookId The book to clear position for
     * @return [AppResult.Success] when the DAO write commits; [AppResult.Failure] only on local DB-write failure.
     */
    suspend fun delete(bookId: BookId): AppResult<Unit>

    /**
     * Mark a book as complete.
     *
     * Optimistic local update followed by server sync.
     * Sets isFinished=true, finishedAt to the provided value (or now).
     * On server failure, rolls back to previous state.
     *
     * @param bookId The book to mark as complete
     * @param startedAt Optional start date in epoch milliseconds (overrides existing)
     * @param finishedAt Optional finish date in epoch milliseconds (defaults to now)
     * @return Result with Unit on success, or Failure on error
     */
    suspend fun markComplete(
        bookId: BookId,
        startedAt: Long? = null,
        finishedAt: Long? = null,
    ): AppResult<Unit>

    /**
     * Discard all progress for a book.
     *
     * Local position row is reset (position=0, isFinished=false) and a
     * DISCARD_PROGRESS pending-op is queued for the server. The pending-op
     * is persisted locally; server propagation returns with the playback sync domain.
     *
     * @param bookId The book to discard progress for
     * @return [AppResult.Success] when the local write + queue commit;
     *   [AppResult.Failure] only on local DB-write failure.
     */
    suspend fun discardProgress(bookId: BookId): AppResult<Unit>

    /**
     * Restart a book from the beginning.
     *
     * Local position row is reset (position=0, isFinished=false, fresh startedAt)
     * and a RESTART_BOOK pending-op is queued for the server. The pending-op
     * is persisted locally; server propagation returns with the playback sync domain.
     *
     * @param bookId The book to restart
     * @return [AppResult.Success] when the local write + queue commit;
     *   [AppResult.Failure] only on local DB-write failure.
     */
    suspend fun restartBook(bookId: BookId): AppResult<Unit>

    /**
     * Single canonical entry point for every mutation of `playback_positions`.
     *
     * The repository owns the per-book Mutex + transaction discipline; callers
     * just specify intent via the [PlaybackUpdate] variant. Concurrent writes
     * for the same book serialize via a per-book Mutex; different books proceed
     * in parallel. Every variant handler runs inside `TransactionRunner.atomically`
     * so partial-write states are impossible.
     *
     * @param bookId The book whose playback state is being mutated.
     * @param update The intent describing the mutation.
     * @return [AppResult.Success] if the transaction committed; [AppResult.Failure]
     *   if the underlying DAO write threw or the transaction rolled back.
     *   `CancellationException` is rethrown.
     */
    suspend fun savePlaybackState(
        bookId: BookId,
        update: PlaybackUpdate,
    ): AppResult<Unit>

    /**
     * Read the most recently played book (highest `lastPlayedAt`) or null if no
     * books have been played. Used by the system UI resumption surface
     * (Android Auto, Wear OS, system notifications after reboot).
     *
     * @return [AppResult.Success] wrapping [LastPlayedInfo] for the most recently
     *   played book, or null if no books have been played;
     *   [AppResult.Failure] if the DAO threw.
     */
    suspend fun getLastPlayedBook(): AppResult<LastPlayedInfo?>
}
