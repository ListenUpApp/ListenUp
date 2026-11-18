package com.calypsan.listenup.client.presentation.discover

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.activity.RealListen
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
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
        bookCoverHash = null,
        isReread = false,
        durationMs = durationMs,
        milestoneValue = 0,
        milestoneUnit = null,
        shelfId = null,
        shelfName = null,
        isYou = false,
    )

/**
 * The feed shows one line per sitting with a book, by the shared rule ([com.calypsan.listenup.api.dto.activity.groupIntoSittings]),
 * and none for a sitting too short to be news. The raw rows are one per span, so a pause to make coffee otherwise
 * fragments a single evening into a wall of entries.
 *
 * The list arrives most-recent-first, matching the feed's `occurred_at DESC` ordering.
 */
class ActivitySittingsTest :
    FunSpec({

        test("two sessions in the same sitting are one line, carrying their summed duration") {
            val feed =
                listOf(
                    session("newer", occurredAt = 110 * MINUTE, durationMs = 10 * MINUTE),
                    session("older", occurredAt = 99 * MINUTE, durationMs = 30_000L),
                )

            val shown = feed.asSittings()

            shown shouldHaveSize 1
            shown[0].id shouldBe "newer"
            shown[0].durationMs shouldBe 10 * MINUTE + 30_000L
            // The line is stamped with the END of the sitting, so it sorts as recent.
            shown[0].occurredAt shouldBe 110 * MINUTE
        }

        test("sessions separated by more than the gap stay separate") {
            val feed =
                listOf(
                    session("newer", occurredAt = 500 * MINUTE, durationMs = 5 * MINUTE),
                    session("older", occurredAt = 100 * MINUTE, durationMs = 5 * MINUTE),
                )

            feed.asSittings() shouldHaveSize 2
        }

        test("sessions for different books never merge") {
            val feed =
                listOf(
                    session("a", occurredAt = 100 * MINUTE, durationMs = MINUTE, bookId = "book-1"),
                    session("b", occurredAt = 99 * MINUTE, durationMs = MINUTE, bookId = "book-2"),
                )

            feed.asSittings() shouldHaveSize 2
        }

        test("someone else's row between two sessions does not split the sitting") {
            // ⛔ The old merge joined only adjacent rows, so anyone else's activity landing between two of Kyle's
            // spans split his evening into two lines, and could drop both halves as too short.
            val feed =
                listOf(
                    session("newer", occurredAt = 106 * MINUTE, durationMs = 40_000L),
                    session("other", occurredAt = 104 * MINUTE, durationMs = 5 * MINUTE, userId = "user-2"),
                    session("older", occurredAt = 100 * MINUTE, durationMs = 30_000L),
                )

            val shown = feed.asSittings()

            shown.map { it.id } shouldBe listOf("newer", "other")
            shown[0].durationMs shouldBe 70_000L
        }

        test("non-listening activities pass through untouched, in place") {
            val finished =
                session("finished", occurredAt = 101 * MINUTE, durationMs = 0L)
                    .copy(type = ActivityType.FINISHED_BOOK, bookId = "book-2")
            val feed =
                listOf(
                    session("newer", occurredAt = 102 * MINUTE, durationMs = MINUTE),
                    finished,
                    session("older", occurredAt = 100 * MINUTE, durationMs = 5 * MINUTE, bookId = "book-3"),
                )

            feed.asSittings() shouldBe feed
        }

        test("a listening session with no book is left alone") {
            val feed =
                listOf(
                    session("a", occurredAt = 100 * MINUTE, durationMs = MINUTE, bookId = null),
                    session("b", occurredAt = 99 * MINUTE, durationMs = MINUTE, bookId = null),
                )

            feed.asSittings() shouldBe feed
        }

        test("a listening session with no book is judged alone, and dropped under a real listen") {
            listOf(session("tap", occurredAt = 100 * MINUTE, durationMs = 8_000L, bookId = null)).asSittings().shouldBeEmpty()
        }

        test("an empty feed stays empty") {
            emptyList<ActivityUiModel>().asSittings() shouldBe emptyList()
        }

        test("a sitting shorter than a real listen is dropped from the feed") {
            listOf(session("tap", occurredAt = 100 * MINUTE, durationMs = 8_000L)).asSittings().shouldBeEmpty()
        }

        test("short sessions that add up to a real listen in one sitting stay, as one line") {
            // 40s and 30s five minutes apart: two fragments, one 70s sitting.
            val feed =
                listOf(
                    session("newer", occurredAt = 105 * MINUTE, durationMs = 40_000L),
                    session("older", occurredAt = 100 * MINUTE, durationMs = 30_000L),
                )

            val shown = feed.asSittings()

            shown shouldHaveSize 1
            shown[0].durationMs shouldBe 70_000L
        }

        test("a sitting of exactly the threshold is a real listen") {
            listOf(session("minute", occurredAt = 100 * MINUTE, durationMs = RealListen.THRESHOLD_MS)).asSittings() shouldHaveSize 1
        }

        test("non-listening activities are never dropped, however short") {
            val started = session("started", occurredAt = 100 * MINUTE, durationMs = 0L).copy(type = ActivityType.STARTED_BOOK)

            listOf(started).asSittings() shouldBe listOf(started)
        }
    })
