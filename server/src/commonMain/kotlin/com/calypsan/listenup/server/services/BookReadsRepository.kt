@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.sqldelight.Book_reads
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** A single completion event: who finished what, when, and how. */
data class BookReadRow(
    val userId: String,
    val bookId: String,
    val finishedAt: Long,
    val source: String,
)

/**
 * Server-only persistence for per-completion read history — the single finished-books primitive.
 *
 * A genuine re-read stacks as a distinct row; a below-threshold replay instead advances the existing
 * row's `finished_at` in place (see [recordCompletion]), so the log is append-mostly rather than
 * strictly append-only. Rows are never deleted. Used by the readership RPC surface to show persistent
 * readers on the Book Detail screen, and counted by [deriveUserStats] for `booksFinished`.
 *
 * This is a **plain** (non-syncable) log — there is no revision / soft-delete substrate, so it persists
 * directly over the generated [ListenUpDatabase.bookReadsQueries] rather than through
 * `SqlSyncableRepository`. [recordCompletion] is the completion write path fired by [StatsRecorder];
 * [recordRead] remains the low-level unconditional append used for test seeding.
 */
class BookReadsRepository(
    private val db: ListenUpDatabase,
    private val clock: Clock = Clock.System,
) {
    /**
     * Append a completion row unconditionally.
     *
     * Live finishes use a fresh UUID for [id], so multiple completions by the same
     * user stack as distinct rows.
     */
    suspend fun recordRead(
        id: String,
        userId: String,
        bookId: String,
        finishedAt: Long,
        source: String,
    ) = suspendTransaction(db) {
        db.bookReadsQueries.insert(
            id = id,
            user_id = userId,
            book_id = bookId,
            finished_at = finishedAt,
            source = source,
            created_at = clock.now().toEpochMilliseconds(),
        )
    }

    /**
     * Record a completion of [bookId] by [userId] at [finishedAtMs], applying the re-read coverage
     * rule: a new [book_reads] row is appended only when the user covered at least
     * [RE_READ_COVERAGE_THRESHOLD] of the book's duration since their previous finish — a genuine
     * re-read. A below-threshold replay (e.g. finishing, then rewinding just the last chapter and
     * "finishing" again) instead moves the existing row's `finished_at` forward, so it counts as the
     * same read. The first-ever finish always appends; when the book's duration is unknown or zero the
     * coverage can't be assessed, so it appends (matching the pre-rule always-append behavior).
     *
     * The whole decision runs in one transaction so a concurrent completion can't interleave between
     * the coverage read and the write.
     */
    suspend fun recordCompletion(
        userId: String,
        bookId: String,
        finishedAtMs: Long,
    ) {
        val createdAt = clock.now().toEpochMilliseconds()
        suspendTransaction(db) {
            // The id of the existing read to merge this finish into, or null to append a new read. The
            // first-ever finish (no previous row) always appends.
            val mergeIntoId: String? =
                db.bookReadsQueries.latestFinishForUserBook(userId, bookId).executeAsOneOrNull()?.let { previous ->
                    val duration = db.booksQueries.durationForBook(bookId).executeAsOneOrNull()
                    val coverage =
                        db.listeningEventsQueries
                            .sumPositionDeltaForBookSince(
                                userId = userId,
                                bookId = bookId,
                                sinceMs = previous.finished_at,
                            ).executeAsOne()
                    val isNewRead =
                        duration == null || duration <= 0 ||
                            coverage >= (duration * RE_READ_COVERAGE_THRESHOLD).toLong()
                    if (isNewRead) null else previous.id
                }
            if (mergeIntoId == null) {
                db.bookReadsQueries.insert(
                    id = Uuid.random().toString(),
                    user_id = userId,
                    book_id = bookId,
                    finished_at = finishedAtMs,
                    source = "playback",
                    created_at = createdAt,
                )
            } else {
                db.bookReadsQueries.updateFinishedAtById(finished_at = finishedAtMs, id = mergeIntoId)
            }
        }
    }

    /** All completions of [bookId] across all users, newest-first. */
    suspend fun finishesForBook(bookId: String): List<BookReadRow> =
        suspendTransaction(db) {
            db.bookReadsQueries
                .finishesForBook(bookId)
                .executeAsList()
                .map { it.toRow() }
        }

    /** All completion timestamps for a single user+book pair, newest-first. */
    suspend fun finishesForUserBook(
        userId: String,
        bookId: String,
    ): List<Long> =
        suspendTransaction(db) {
            db.bookReadsQueries
                .finishesForUserBook(userId, bookId)
                .executeAsList()
        }

    private fun Book_reads.toRow(): BookReadRow =
        BookReadRow(
            userId = user_id,
            bookId = book_id,
            finishedAt = finished_at,
            source = source,
        )

    private companion object {
        /**
         * A completion counts as a genuine re-read only when the user covered at least this fraction of
         * the book's duration since their previous finish. Because coverage is derived from the
         * `listening_events` primitive, this threshold is retroactively tunable — change it and run
         * `BulkRecompute` to re-settle history.
         */
        const val RE_READ_COVERAGE_THRESHOLD = 0.5
    }
}
