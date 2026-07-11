package com.calypsan.listenup.api.dto.readingorder

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/**
 * Round-trip coverage for the reading-order write DTOs — the outbox payloads
 * (`ReadingOrderUpdate`, the sealed [ReadingOrderBookWrite]) and the follow-state
 * request. The sealed hierarchy round-trips through [contractJson] specifically,
 * proving the polymorphic discriminator survives the production wire config.
 */
class ReadingOrderWritesTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

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
