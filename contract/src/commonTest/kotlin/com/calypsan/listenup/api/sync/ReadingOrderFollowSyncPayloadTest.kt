package com.calypsan.listenup.api.sync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class ReadingOrderFollowSyncPayloadTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        test("ReadingOrderFollowSyncPayload round-trips") {
            val p =
                ReadingOrderFollowSyncPayload(
                    id = "u1:series-1",
                    seriesId = "series-1",
                    activeReadingOrderId = "ro1",
                    revision = 3L,
                    updatedAt = 100L,
                    createdAt = 50L,
                    deletedAt = null,
                )
            json.decodeFromString<ReadingOrderFollowSyncPayload>(json.encodeToString(p)) shouldBe p
        }

        test("ReadingOrderFollowSyncPayload preserves a null activeReadingOrderId (the graceful floor)") {
            val p =
                ReadingOrderFollowSyncPayload(
                    id = "u1:series-1",
                    seriesId = "series-1",
                    activeReadingOrderId = null,
                    revision = 1L,
                    updatedAt = 100L,
                    createdAt = 50L,
                    deletedAt = null,
                )
            json
                .decodeFromString<ReadingOrderFollowSyncPayload>(json.encodeToString(p))
                .activeReadingOrderId shouldBe null
        }
    })
