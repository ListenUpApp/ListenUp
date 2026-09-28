package com.calypsan.listenup.client.features.bookdetail.components

import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.readers.Reader
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
    })
