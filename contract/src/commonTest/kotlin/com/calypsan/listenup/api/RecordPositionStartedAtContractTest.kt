package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.RecordPositionRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * The start day a reader picks in "Mark as finished" rides [RecordPositionRequest.startedAt]. It is
 * additive: a request from a client that predates it decodes with no start, and the server then dates
 * the read as it always has.
 */
class RecordPositionStartedAtContractTest :
    FunSpec({
        test("a picked start survives the wire") {
            val request =
                RecordPositionRequest(
                    bookId = "b1",
                    positionMs = 0L,
                    lastPlayedAt = 1_759_000_000_000L,
                    finished = true,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                    finishedAt = 1_759_000_000_000L,
                    startedAt = 1_756_700_000_000L,
                )

            val back =
                contractJson.decodeFromString(
                    RecordPositionRequest.serializer(),
                    contractJson.encodeToString(RecordPositionRequest.serializer(), request),
                )

            back shouldBe request
            back.startedAt shouldBe 1_756_700_000_000L
        }

        test("a request from an older client, without a start, still decodes with none") {
            val legacy =
                """{"bookId":"b1","positionMs":0,"lastPlayedAt":5,"finished":true,""" +
                    """"playbackSpeed":1.0,"currentChapterId":null,"finishedAt":5}"""

            contractJson.decodeFromString(RecordPositionRequest.serializer(), legacy).startedAt.shouldBeNull()
        }
    })
