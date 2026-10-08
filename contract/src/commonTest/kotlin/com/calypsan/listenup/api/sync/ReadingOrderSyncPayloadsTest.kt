package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderChoiceKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReadingOrderSyncPayloadsTest :
    FunSpec({
        test("an order round-trips") {
            val p = ReadingOrderSyncPayload("ro", "cosmere", "Ultimate Read Order", "simon", 3, 2, 1, null)
            contractJson.decodeFromString<ReadingOrderSyncPayload>(contractJson.encodeToString(p)) shouldBe p
        }

        test("a membership round-trips") {
            val p = ReadingOrderBookSyncPayload("m1", "ro", "b1", 4, 7, 2, 1, null)
            contractJson.decodeFromString<ReadingOrderBookSyncPayload>(contractJson.encodeToString(p)) shouldBe p
        }

        test("a follow round-trips, and a missing or unknown kind reads as SERIES") {
            val p = ReadingOrderFollowSyncPayload("u:s", "s", ReadingOrderChoiceKind.ORDER, "ro", 9, 2, 1, null)
            contractJson.decodeFromString<ReadingOrderFollowSyncPayload>(contractJson.encodeToString(p)) shouldBe p
            contractJson
                .decodeFromString<ReadingOrderFollowSyncPayload>(
                    """{"id":"u:s","seriesId":"s","revision":1,"updatedAt":1,"createdAt":1}""",
                ).choice shouldBe ReadingOrderChoiceKind.SERIES
            contractJson
                .decodeFromString<ReadingOrderFollowSyncPayload>(
                    """{"id":"u:s","seriesId":"s","choice":"CHRONOLOGICAL","revision":1,"updatedAt":1,"createdAt":1}""",
                ).choice shouldBe ReadingOrderChoiceKind.SERIES
        }

        test("the three domain keys carry the wire names clients and server share") {
            SyncDomains.READING_ORDERS.name shouldBe "reading_orders"
            SyncDomains.READING_ORDER_BOOKS.name shouldBe "reading_order_books"
            SyncDomains.READING_ORDER_FOLLOWS.name shouldBe "reading_order_follows"
        }
    })
