package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.ShelfBookFactRow
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ShelfTileFactsTest :
    FunSpec({
        fun fact(
            bookId: String,
            sortOrder: Int,
            coverHash: String? = "h-$bookId",
            durationMs: Long = 1_000L,
        ) = ShelfBookFactRow(
            shelfId = "s1",
            bookId = bookId,
            sortOrder = sortOrder,
            coverHash = coverHash,
            coverDownloadedAt = null,
            totalDuration = durationMs,
        )

        test("covers are the first four books with a cover hash by sortOrder, whatever order the rows arrive in") {
            val rows = listOf(fact("e", 4), fact("c", 2), fact("a", 0), fact("d", 3, coverHash = null), fact("b", 1), fact("f", 5))

            shelfTileFactsOf(rows).covers.map { it.bookId } shouldBe listOf("a", "b", "c", "e")
        }

        test("the total counts every book present in Room, covered or not") {
            val rows = listOf(fact("a", 0, durationMs = 1_000L), fact("b", 1, coverHash = null, durationMs = 2_500L))

            shelfTileFactsOf(rows).totalDurationMs shouldBe 3_500L
        }

        test("a cover row keeps its download time") {
            val row = fact("a", 0).copy(coverDownloadedAt = Timestamp(7L))

            shelfTileFactsOf(listOf(row)).covers.single().coverDownloadedAt shouldBe Timestamp(7L)
        }

        test("a shelf with no books present is empty") {
            shelfTileFactsOf(emptyList()) shouldBe ShelfTileFacts.Empty
        }
    })
