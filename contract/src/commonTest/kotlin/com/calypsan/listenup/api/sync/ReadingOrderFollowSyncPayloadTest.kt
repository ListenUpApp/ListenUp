package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderBookWrite
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderUpdate
import com.calypsan.listenup.api.dto.readingorder.SetActiveReadingOrderRequest
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
            json.decodeFromString<ReadingOrderFollowSyncPayload>(json.encodeToString(p)).activeReadingOrderId shouldBe null
        }

        test("ReadingOrderUpdate round-trips") {
            val p =
                ReadingOrderUpdate(
                    name = "Cosmere",
                    description = "chrono",
                    attribution = "u/Argent",
                    isPrivate = true,
                )
            json.decodeFromString<ReadingOrderUpdate>(json.encodeToString(p)) shouldBe p
        }

        test("ReadingOrderBookWrite subtypes round-trip polymorphically through contractJson") {
            val writes: List<ReadingOrderBookWrite> =
                listOf(
                    ReadingOrderBookWrite.Add(readingOrderId = "ro1", bookId = "b1"),
                    ReadingOrderBookWrite.Remove(readingOrderId = "ro1", bookId = "b1"),
                    ReadingOrderBookWrite.Reorder(readingOrderId = "ro1", orderedBookIds = listOf("b2", "b1")),
                )
            writes.forEach { write ->
                val encoded = contractJson.encodeToString(ReadingOrderBookWrite.serializer(), write)
                contractJson.decodeFromString(ReadingOrderBookWrite.serializer(), encoded) shouldBe write
            }
        }

        test("SetActiveReadingOrderRequest round-trips") {
            val p = SetActiveReadingOrderRequest(seriesId = "series-1", activeReadingOrderId = "ro1")
            json.decodeFromString<SetActiveReadingOrderRequest>(json.encodeToString(p)) shouldBe p
        }
    })
