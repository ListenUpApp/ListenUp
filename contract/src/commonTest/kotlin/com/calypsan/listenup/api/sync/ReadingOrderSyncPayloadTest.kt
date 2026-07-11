package com.calypsan.listenup.api.sync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class ReadingOrderSyncPayloadTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        test("ReadingOrderSyncPayload round-trips") {
            val p =
                ReadingOrderSyncPayload(
                    id = "ro1",
                    name = "Cosmere — Chronological",
                    description = "by publication",
                    attribution = "u/Argent",
                    isPrivate = false,
                    revision = 3L,
                    updatedAt = 100L,
                    createdAt = 50L,
                    deletedAt = null,
                )
            json.decodeFromString<ReadingOrderSyncPayload>(json.encodeToString(p)) shouldBe p
        }

        test("ReadingOrderBookSyncPayload preserves sortOrder") {
            val p =
                ReadingOrderBookSyncPayload(
                    id = "ro1:b2",
                    readingOrderId = "ro1",
                    bookId = "b2",
                    sortOrder = 7,
                    revision = 1L,
                    updatedAt = 100L,
                    createdAt = 50L,
                    deletedAt = null,
                )
            json.decodeFromString<ReadingOrderBookSyncPayload>(json.encodeToString(p)) shouldBe p
        }
    })
