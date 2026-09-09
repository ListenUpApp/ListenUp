package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class PlaybackVolumeBoostContractTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        test("PlaybackPositionSyncPayload round-trips volumeBoostDb and measuredGainDb") {
            val p =
                PlaybackPositionSyncPayload(
                    id = "b1",
                    bookId = "b1",
                    positionMs = 1000L,
                    lastPlayedAt = 5L,
                    finished = false,
                    playbackSpeed = 1.5f,
                    volumeBoostDb = 6.0f,
                    measuredGainDb = -2.5f,
                    currentChapterId = null,
                    revision = 1L,
                    updatedAt = 5L,
                    createdAt = 1L,
                    deletedAt = null,
                )
            val back =
                json.decodeFromString(
                    PlaybackPositionSyncPayload.serializer(),
                    json.encodeToString(PlaybackPositionSyncPayload.serializer(), p),
                )
            back.volumeBoostDb shouldBe 6.0f
            back.measuredGainDb shouldBe -2.5f
        }

        test("an old payload without the new fields still decodes (forward-compat defaults)") {
            val old =
                """{"id":"b1","bookId":"b1","positionMs":0,"lastPlayedAt":0,"finished":false,""" +
                    """"playbackSpeed":1.0,"currentChapterId":null,"revision":0,"updatedAt":0,"createdAt":0,"deletedAt":null}"""
            val back = json.decodeFromString(PlaybackPositionSyncPayload.serializer(), old)
            back.volumeBoostDb shouldBe 0.0f
            back.measuredGainDb shouldBe null
        }

        test("PlaybackPositionSyncPayload round-trips finishedAt, hasCustomSpeed and hasCustomBoost") {
            val p =
                PlaybackPositionSyncPayload(
                    id = "b1",
                    bookId = "b1",
                    positionMs = 1000L,
                    lastPlayedAt = 5L,
                    finished = true,
                    playbackSpeed = 1.5f,
                    currentChapterId = null,
                    finishedAt = 1_730_000_000_000L,
                    hasCustomSpeed = true,
                    hasCustomBoost = true,
                    revision = 1L,
                    updatedAt = 5L,
                    createdAt = 1L,
                    deletedAt = null,
                )
            val back =
                json.decodeFromString(
                    PlaybackPositionSyncPayload.serializer(),
                    json.encodeToString(PlaybackPositionSyncPayload.serializer(), p),
                )
            back.finishedAt shouldBe 1_730_000_000_000L
            back.hasCustomSpeed shouldBe true
            back.hasCustomBoost shouldBe true
        }

        test("a payload without the cross-device fields still decodes") {
            val old =
                """{"id":"b1","bookId":"b1","positionMs":0,"lastPlayedAt":0,"finished":false,""" +
                    """"playbackSpeed":1.0,"currentChapterId":null,"revision":0,"updatedAt":0,"createdAt":0,"deletedAt":null}"""
            val back = json.decodeFromString(PlaybackPositionSyncPayload.serializer(), old)
            back.finishedAt shouldBe null
            back.hasCustomSpeed shouldBe false
            back.hasCustomBoost shouldBe false
        }

        test("RecordPositionRequest round-trips the cross-device fields and defaults them when absent") {
            val request =
                RecordPositionRequest(
                    bookId = "b1",
                    positionMs = 1000L,
                    lastPlayedAt = 5L,
                    finished = true,
                    playbackSpeed = 1.5f,
                    currentChapterId = null,
                    finishedAt = 1_730_000_000_000L,
                    hasCustomSpeed = true,
                    hasCustomBoost = true,
                )
            val back =
                json.decodeFromString(
                    RecordPositionRequest.serializer(),
                    json.encodeToString(RecordPositionRequest.serializer(), request),
                )
            back.finishedAt shouldBe 1_730_000_000_000L
            back.hasCustomSpeed shouldBe true
            back.hasCustomBoost shouldBe true

            val old =
                """{"bookId":"b1","positionMs":0,"lastPlayedAt":0,"finished":false,""" +
                    """"playbackSpeed":1.0,"currentChapterId":null}"""
            val decodedOld = json.decodeFromString(RecordPositionRequest.serializer(), old)
            decodedOld.finishedAt shouldBe null
            decodedOld.hasCustomSpeed shouldBe false
            decodedOld.hasCustomBoost shouldBe false
        }
    })
