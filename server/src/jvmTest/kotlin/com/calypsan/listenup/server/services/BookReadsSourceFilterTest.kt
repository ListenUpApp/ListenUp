package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

private const val USER = "u1"
private const val BOOK = "book-1"
private const val OTHER_BOOK = "book-2"

// 2026-05-22 12:00:00 UTC.
private const val NOW = 1_779_451_200_000L
private const val DAY = 86_400_000L

private fun ListenUpDatabase.seedRead(
    id: String,
    finishedAt: Long,
    source: String,
    bookId: String = BOOK,
) = bookReadsQueries.insert(
    id = id,
    user_id = USER,
    book_id = bookId,
    finished_at = finishedAt,
    source = source,
    created_at = finishedAt,
)

private fun ListenUpDatabase.seedFinishedPosition(lastPlayedAt: Long) =
    playbackPositionsQueries.insert(
        id = "pos-$USER-$BOOK",
        user_id = USER,
        book_id = BOOK,
        position_ms = 3_600_000L,
        last_played_at = lastPlayedAt,
        finished = 1L,
        playback_speed = 1.0,
        volume_boost_db = 0.0,
        measured_gain_db = null,
        finished_at = null,
        has_custom_speed = 0L,
        has_custom_boost = 0L,
        current_chapter_id = null,
        revision = 0L,
        created_at = 0L,
        updated_at = 0L,
        deleted_at = null,
        client_op_id = null,
    )

/**
 * A read pulled from Hardcover (`source = 'hardcover'`) is shown but never counts as listening (#601
 * B3). One test per consumer in the source-filter audit; each fails if its query forgets the filter.
 */
class BookReadsSourceFilterTest :
    FunSpec({
        fun rig(block: suspend ListenUpDatabase.() -> Unit) =
            withSqlDatabase {
                sql.seedTestUser(USER)
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook(BOOK)
                sql.seedTestBook(OTHER_BOOK)
                // A known length, so the coverage rule can judge a replay.
                driver.execute(null, "UPDATE books SET total_duration = 36000000 WHERE id = '$BOOK'", 0)
                runTest { sql.block() }
            }

        test("the coverage rule never merges a ListenUp finish into a pulled Hardcover read") {
            rig {
                seedRead("hc", finishedAt = NOW - DAY, source = BookReadSource.HARDCOVER)
                val reads = BookReadsRepository(db = this)

                // With the filter this is the first ListenUp finish, so it appends.
                reads.recordCompletion(USER, BOOK, finishedAtMs = NOW) shouldBe true

                bookReadsQueries.finishesForBook(BOOK).executeAsList().map { it.source to it.finished_at } shouldContainExactlyInAnyOrder
                    listOf(BookReadSource.HARDCOVER to NOW - DAY, BookReadSource.PLAYBACK to NOW)
            }
        }

        test("a stats rebuild still recovers a lost ListenUp finish when only a Hardcover read exists") {
            rig {
                seedFinishedPosition(lastPlayedAt = NOW)
                seedRead("hc", finishedAt = NOW - DAY, source = BookReadSource.HARDCOVER)

                reconcileBookReadsFromPositions(this, USER, nowMs = NOW)

                bookReadsQueries.finishesForBook(BOOK).executeAsList().map { it.source } shouldContainExactlyInAnyOrder
                    listOf(BookReadSource.HARDCOVER, BookReadSource.RECONCILE)
            }
        }

        test("books finished, the streak and the per-book finish list count only ListenUp reads") {
            rig {
                seedRead("hc1", finishedAt = NOW, source = BookReadSource.HARDCOVER)
                seedRead("hc2", finishedAt = NOW - DAY, source = BookReadSource.HARDCOVER)

                val stats = deriveUserStats(sql = this, userId = USER, nowMs = NOW)

                stats.booksFinished shouldBe 0
                stats.currentStreakDays shouldBe 0
                stats.longestStreakDays shouldBe 0
                BookReadsRepository(db = this).finishesForUserBook(USER, BOOK) shouldBe emptyList()
            }
        }

        test("the leaderboard's books-finished windows ignore pulled reads") {
            rig {
                // Two different books: the windows count DISTINCT books, so one book read twice would
                // count 1 with or without the filter.
                seedRead("hc", finishedAt = NOW - DAY, source = BookReadSource.HARDCOVER, bookId = OTHER_BOOK)
                seedRead("lu", finishedAt = NOW - 2 * DAY, source = BookReadSource.PLAYBACK)
                val repo = PublicProfileRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                PublicProfileMaintainer(sql = this, publicProfileRepo = repo, clock = FixedClock(Instant.fromEpochMilliseconds(NOW)))
                    .refresh(USER)

                val saved = repo.pullSince(userId = null, cursor = 0, limit = 10).items.single()
                saved.booksFinishedLast7Days shouldBe 1
                saved.booksFinishedLast30Days shouldBe 1
                saved.booksFinishedLast365Days shouldBe 1
            }
        }
    })
