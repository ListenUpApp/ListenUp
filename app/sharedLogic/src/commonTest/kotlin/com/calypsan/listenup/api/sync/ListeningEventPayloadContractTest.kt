package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ListeningEventPayloadContractTest :
    FunSpec({

        test("ListeningEventSyncPayload round-trips with deletedAt = null") {
            val original =
                ListeningEventSyncPayload(
                    id = "evt-1",
                    bookId = "book-1",
                    startPositionMs = 0L,
                    endPositionMs = 300_000L,
                    startedAt = 1_730_000_000_000L,
                    endedAt = 1_730_000_300_000L,
                    playbackSpeed = 1.0f,
                    tz = "Europe/London",
                    deviceLabel = "Simon's iPhone",
                    revision = 1L,
                    updatedAt = 1_730_000_300_000L,
                    createdAt = 1_730_000_000_000L,
                    deletedAt = null,
                )
            val encoded = contractJson.encodeToString(ListeningEventSyncPayload.serializer(), original)
            contractJson.decodeFromString(ListeningEventSyncPayload.serializer(), encoded) shouldBe original
        }

        test("ListeningEventSyncPayload round-trips with deletedAt non-null (tombstone)") {
            val original =
                ListeningEventSyncPayload(
                    id = "evt-2",
                    bookId = "book-2",
                    startPositionMs = 600_000L,
                    endPositionMs = 900_000L,
                    startedAt = 1_730_001_000_000L,
                    endedAt = 1_730_001_300_000L,
                    playbackSpeed = 1.5f,
                    tz = "America/New_York",
                    deviceLabel = null,
                    revision = 5L,
                    updatedAt = 1_730_002_000_000L,
                    createdAt = 1_730_001_000_000L,
                    deletedAt = 1_730_003_000_000L,
                )
            val encoded = contractJson.encodeToString(ListeningEventSyncPayload.serializer(), original)
            contractJson.decodeFromString(ListeningEventSyncPayload.serializer(), encoded) shouldBe original
        }
    })
