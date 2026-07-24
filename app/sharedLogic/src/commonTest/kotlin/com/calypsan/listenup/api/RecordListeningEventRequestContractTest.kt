package com.calypsan.listenup.api

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RecordListeningEventRequestContractTest :
    FunSpec({

        test("RecordListeningEventRequest round-trips with deviceLabel = null") {
            val original =
                RecordListeningEventRequest(
                    id = "evt-1",
                    bookId = "book-1",
                    startPositionMs = 0L,
                    endPositionMs = 300_000L,
                    startedAt = 1_730_000_000_000L,
                    endedAt = 1_730_000_300_000L,
                    playbackSpeed = 1.0f,
                    tz = "Europe/London",
                    deviceLabel = null,
                )
            val encoded = contractJson.encodeToString(RecordListeningEventRequest.serializer(), original)
            contractJson.decodeFromString(RecordListeningEventRequest.serializer(), encoded) shouldBe original
        }

        test("RecordListeningEventRequest round-trips with deviceLabel non-null") {
            val original =
                RecordListeningEventRequest(
                    id = "evt-2",
                    bookId = "book-2",
                    startPositionMs = 600_000L,
                    endPositionMs = 900_000L,
                    startedAt = 1_730_001_000_000L,
                    endedAt = 1_730_001_300_000L,
                    playbackSpeed = 1.5f,
                    tz = "America/New_York",
                    deviceLabel = "Simon's MacBook",
                )
            val encoded = contractJson.encodeToString(RecordListeningEventRequest.serializer(), original)
            contractJson.decodeFromString(RecordListeningEventRequest.serializer(), encoded) shouldBe original
        }
    })
