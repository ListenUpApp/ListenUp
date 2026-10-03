package com.calypsan.listenup.client.features.bookdetail.components

import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.util.relativeOrMonthYear
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/** How ratings reach reader rows. */
class ReaderRowsRatingTest :
    FunSpec({
        fun rating(
            userId: String,
            halfStars: Int,
            note: String? = null,
        ) = ListenerRating(bookId = "b1", userId = userId, halfStars = halfStars, note = note, ratedAtMs = 1L)

        test("a rated-only line has neither a progress bar nor a finished date") {
            val readers =
                listOf(
                    Reader(
                        userId = "u1",
                        displayName = "Ada",
                        isYou = false,
                        currentProgressPct = null,
                        finishes = emptyList(),
                        rating = rating("u1", 7, note = "Loved it"),
                    ),
                )

            val row = readers.toReaderRows(nowMs = 10_000L).single()

            row.isRatedOnly shouldBe true
            row.isReading shouldBe false
            row.progressPct shouldBe null
            row.finishedWhen shouldBe null
            row.halfStars shouldBe 7
            row.note shouldBe "Loved it"
        }

        test("only a person's first line carries their stars") {
            val readers =
                listOf(
                    Reader(
                        userId = "u1",
                        displayName = "Ada",
                        isYou = false,
                        currentProgressPct = 40,
                        finishes = listOf(5_000L),
                        rating = rating("u1", 10),
                    ),
                )

            val rows = readers.toReaderRows(nowMs = 10_000L)

            rows shouldHaveSize 2
            rows[0].halfStars shouldBe 10
            rows[0].isRatedOnly shouldBe false
            rows[1].halfStars shouldBe null
        }

        test("a Hardcover read is its own row, marked as from Hardcover, with its date") {
            val readers =
                listOf(
                    Reader(
                        userId = "u1",
                        displayName = "Ada",
                        isYou = false,
                        currentProgressPct = null,
                        finishes = listOf(9_000L),
                        hardcoverFinishes = listOf(5_000L),
                    ),
                )

            val rows = readers.toReaderRows(nowMs = 10_000L)

            rows shouldHaveSize 2
            rows[0].isOnHardcover shouldBe false
            rows[1].isOnHardcover shouldBe true
            rows[1].finishedWhen shouldBe relativeOrMonthYear(5_000L, 10_000L)
            rows[1].isRatedOnly shouldBe false
        }

        test("a finish also logged on Hardcover is one row, flagged as also on Hardcover") {
            val readers =
                listOf(
                    Reader(
                        userId = "u1",
                        displayName = "Ada",
                        isYou = false,
                        currentProgressPct = null,
                        finishes = listOf(9_000L),
                        finishesAlsoOnHardcover = listOf(9_000L),
                    ),
                )

            val row = readers.toReaderRows(nowMs = 10_000L).single()

            row.isAlsoOnHardcover shouldBe true
            row.isOnHardcover shouldBe false
            row.finishedWhen shouldBe relativeOrMonthYear(9_000L, 10_000L)
        }

        test("a Hardcover read and a ListenUp finish shown with the same date are different list items") {
            val readers =
                listOf(
                    Reader(
                        userId = "u1",
                        displayName = "Ada",
                        isYou = false,
                        currentProgressPct = null,
                        finishes = listOf(5_000L),
                        hardcoverFinishes = listOf(5_000L),
                    ),
                )

            val rows = readers.toReaderRows(nowMs = 10_000L)

            rows.map { it.finishedWhen }.distinct() shouldHaveSize 1
            rows.map { it.listKey }.distinct() shouldHaveSize 2
        }

        test("two ListenUp finishes by one reader in the same month are different list items") {
            val dayMs = 86_400_000L
            val nowMs = 400 * dayMs
            val readers =
                listOf(
                    Reader(
                        userId = "u1",
                        displayName = "Ada",
                        isYou = false,
                        currentProgressPct = null,
                        finishes = listOf(100 * dayMs, 110 * dayMs),
                    ),
                )

            val rows = readers.toReaderRows(nowMs = nowMs)

            rows.map { it.finishedWhen }.distinct() shouldHaveSize 1
            rows.map { it.listKey }.distinct() shouldHaveSize 2
        }
    })
