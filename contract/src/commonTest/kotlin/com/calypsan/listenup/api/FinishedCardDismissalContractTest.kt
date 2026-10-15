package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * S2: dismissing Home's "just finished" card rides the position's own wire, under a stable name, and is additive —
 * a client or server that predates it decodes with none.
 */
class FinishedCardDismissalContractTest :
    FunSpec({
        val request =
            RecordPositionRequest(
                bookId = "b1",
                positionMs = 9_000L,
                lastPlayedAt = 5L,
                finished = true,
                playbackSpeed = 1.0f,
                currentChapterId = null,
                finishedAt = 4L,
                finishedCardDismissedAt = 9L,
            )
        val payload =
            PlaybackPositionSyncPayload(
                id = "p1",
                bookId = "b1",
                positionMs = 9_000L,
                lastPlayedAt = 5L,
                finished = true,
                playbackSpeed = 1.0f,
                currentChapterId = null,
                finishedAt = 4L,
                finishedCardDismissedAt = 9L,
                revision = 3L,
                updatedAt = 6L,
                createdAt = 1L,
                deletedAt = null,
            )

        test("a dismissal survives the request's wire under its stable name") {
            val json = contractJson.encodeToString(RecordPositionRequest.serializer(), request)

            json shouldContain "\"finishedCardDismissedAt\":9"
            contractJson.decodeFromString(RecordPositionRequest.serializer(), json) shouldBe request
        }

        test("a dismissal survives the sync payload's wire under its stable name") {
            val json = contractJson.encodeToString(PlaybackPositionSyncPayload.serializer(), payload)

            json shouldContain "\"finishedCardDismissedAt\":9"
            contractJson.decodeFromString(PlaybackPositionSyncPayload.serializer(), json) shouldBe payload
        }

        test("a request from a client that predates it decodes with none") {
            val legacy =
                """{"bookId":"b1","positionMs":0,"lastPlayedAt":5,"finished":true,""" +
                    """"playbackSpeed":1.0,"currentChapterId":null,"finishedAt":4}"""

            contractJson.decodeFromString(RecordPositionRequest.serializer(), legacy).finishedCardDismissedAt.shouldBeNull()
        }

        test("a payload from a server that predates it decodes with none") {
            val legacy =
                """{"id":"p1","bookId":"b1","positionMs":0,"lastPlayedAt":5,"finished":true,"playbackSpeed":1.0,""" +
                    """"currentChapterId":null,"revision":1,"updatedAt":1,"createdAt":1,"deletedAt":null}"""

            contractJson
                .decodeFromString(PlaybackPositionSyncPayload.serializer(), legacy)
                .finishedCardDismissedAt
                .shouldBeNull()
        }
    })
