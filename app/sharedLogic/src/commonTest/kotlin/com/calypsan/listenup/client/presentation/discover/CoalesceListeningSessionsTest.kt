package com.calypsan.listenup.client.presentation.discover

import com.calypsan.listenup.api.dto.activity.ActivityType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private const val MINUTE = 60_000L

private fun session(
    id: String,
    occurredAt: Long,
    durationMs: Long,
    bookId: String? = "book-1",
    userId: String = "user-1",
): ActivityUiModel =
    ActivityUiModel(
        id = id,
        userId = userId,
        type = ActivityType.LISTENING_SESSION,
        occurredAt = occurredAt,
        userDisplayName = "Simon",
        bookId = bookId,
        bookTitle = "Dungeon Crawler Carl",
        bookAuthorName = "Matt Dinniman",
        bookCoverPath = null,
        isReread = false,
        durationMs = durationMs,
        milestoneValue = 0,
        milestoneUnit = null,
        shelfId = null,
        shelfName = null,
    )

/**
 * One sitting with a book should read as one line in the feed. The raw rows are one-per-span, so a
 * pause to make coffee otherwise fragments a single evening into a wall of entries.
 *
 * The list arrives most-recent-first, matching the feed's `occurred_at DESC` ordering.
 */
class CoalesceListeningSessionsTest :
    FunSpec({

        test("two sessions in the same sitting merge into one, summing their durations") {
            // 30s at T-11min, then 10min ending at T. Reads as a single 10:30 sitting.
            val tenMinutesAgo = 100 * MINUTE
            val feed =
                listOf(
                    session("newer", occurredAt = tenMinutesAgo + 10 * MINUTE, durationMs = 10 * MINUTE),
                    session("older", occurredAt = tenMinutesAgo - 30_000L + 30_000L, durationMs = 30_000L),
                )

            val merged = feed.coalesceListeningSessions()

            merged shouldHaveSize 1
            merged[0].durationMs shouldBe 10 * MINUTE + 30_000L
            // The surviving entry is stamped with the END of the sitting, so it sorts as recent.
            merged[0].occurredAt shouldBe tenMinutesAgo + 10 * MINUTE
        }

        test("sessions separated by more than the gap stay separate") {
            val feed =
                listOf(
                    session("newer", occurredAt = 500 * MINUTE, durationMs = 5 * MINUTE),
                    session("older", occurredAt = 100 * MINUTE, durationMs = 5 * MINUTE),
                )

            val merged = feed.coalesceListeningSessions()

            merged shouldHaveSize 2
        }

        test("sessions for different books never merge") {
            val feed =
                listOf(
                    session("a", occurredAt = 100 * MINUTE, durationMs = MINUTE, bookId = "book-1"),
                    session("b", occurredAt = 99 * MINUTE, durationMs = MINUTE, bookId = "book-2"),
                )

            feed.coalesceListeningSessions() shouldHaveSize 2
        }

        test("sessions from different users never merge") {
            val feed =
                listOf(
                    session("a", occurredAt = 100 * MINUTE, durationMs = MINUTE, userId = "user-1"),
                    session("b", occurredAt = 99 * MINUTE, durationMs = MINUTE, userId = "user-2"),
                )

            feed.coalesceListeningSessions() shouldHaveSize 2
        }

        test("a run of three consecutive sessions collapses to one") {
            val feed =
                listOf(
                    session("c", occurredAt = 102 * MINUTE, durationMs = MINUTE),
                    session("b", occurredAt = 101 * MINUTE, durationMs = MINUTE),
                    session("a", occurredAt = 100 * MINUTE, durationMs = MINUTE),
                )

            val merged = feed.coalesceListeningSessions()

            merged shouldHaveSize 1
            merged[0].durationMs shouldBe 3 * MINUTE
            merged[0].occurredAt shouldBe 102 * MINUTE
        }

        test("non-listening activities pass through untouched and do not join a run") {
            val finished =
                session("finished", occurredAt = 101 * MINUTE, durationMs = 0L)
                    .copy(type = ActivityType.FINISHED_BOOK)
            val feed =
                listOf(
                    session("newer", occurredAt = 102 * MINUTE, durationMs = MINUTE),
                    finished,
                    session("older", occurredAt = 100 * MINUTE, durationMs = MINUTE),
                )

            val merged = feed.coalesceListeningSessions()

            // The finished-book entry separates the two sittings; nothing merges across it.
            merged shouldHaveSize 3
            merged[1].type shouldBe ActivityType.FINISHED_BOOK
        }

        test("a listening session with no book is left alone") {
            val feed =
                listOf(
                    session("a", occurredAt = 100 * MINUTE, durationMs = MINUTE, bookId = null),
                    session("b", occurredAt = 99 * MINUTE, durationMs = MINUTE, bookId = null),
                )

            feed.coalesceListeningSessions() shouldHaveSize 2
        }

        test("an empty feed stays empty") {
            emptyList<ActivityUiModel>().coalesceListeningSessions() shouldBe emptyList()
        }
    })
