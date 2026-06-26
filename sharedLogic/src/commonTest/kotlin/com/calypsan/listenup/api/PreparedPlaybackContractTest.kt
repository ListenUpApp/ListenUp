package com.calypsan.listenup.api

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.PreparedAudioFile
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PreparedPlaybackContractTest :
    FunSpec({

        test("PreparedPlayback round-trips with resumePosition = null") {
            val original =
                PreparedPlayback(
                    bookId = "book-1",
                    audioFiles =
                        listOf(
                            PreparedAudioFile(
                                fileId = "af-1",
                                index = 0,
                                url = "/api/v1/audio/book-1/af-1?u=user1&exp=9999999999&sig=aabbcc",
                                format = "m4b",
                                durationMs = 3_600_000L,
                                sizeBytes = 500_000_000L,
                            ),
                            PreparedAudioFile(
                                fileId = "af-2",
                                index = 1,
                                url = "/api/v1/audio/book-1/af-2?u=user1&exp=9999999999&sig=ddeeff",
                                format = "mp3",
                                durationMs = 1_800_000L,
                                sizeBytes = 250_000_000L,
                            ),
                        ),
                    resumePosition = null,
                )
            val encoded = contractJson.encodeToString(PreparedPlayback.serializer(), original)
            contractJson.decodeFromString(PreparedPlayback.serializer(), encoded) shouldBe original
        }

        test("PreparedPlayback round-trips with resumePosition non-null") {
            val position =
                PlaybackPositionSyncPayload(
                    id = "pos-1",
                    bookId = "book-1",
                    positionMs = 42_000L,
                    lastPlayedAt = 1_730_000_000_000L,
                    finished = false,
                    playbackSpeed = 1.25f,
                    currentChapterId = "chap-1",
                    revision = 3L,
                    updatedAt = 1_730_000_000_000L,
                    createdAt = 1_720_000_000_000L,
                    deletedAt = null,
                )
            val original =
                PreparedPlayback(
                    bookId = "book-1",
                    audioFiles =
                        listOf(
                            PreparedAudioFile(
                                fileId = "af-1",
                                index = 0,
                                url = "/api/v1/audio/book-1/af-1?u=user1&exp=9999999999&sig=aabbcc",
                                format = "m4b",
                                durationMs = 3_600_000L,
                                sizeBytes = 500_000_000L,
                            ),
                        ),
                    resumePosition = position,
                )
            val encoded = contractJson.encodeToString(PreparedPlayback.serializer(), original)
            contractJson.decodeFromString(PreparedPlayback.serializer(), encoded) shouldBe original
        }

        test("PreparedPlayback round-trips with coverUrl set") {
            val original =
                PreparedPlayback(
                    bookId = "b1",
                    audioFiles =
                        listOf(
                            PreparedAudioFile(
                                fileId = "af-1",
                                index = 0,
                                url = "/api/v1/audio/b1/af-1?u=u1&exp=9999999999&sig=aabbcc",
                                format = "m4b",
                                durationMs = 3_600_000L,
                                sizeBytes = 500_000_000L,
                            ),
                        ),
                    resumePosition = null,
                    coverUrl = "/api/v1/cover-cast/b1?u=u1&exp=9999999999&sig=ab",
                )
            val encoded = contractJson.encodeToString(PreparedPlayback.serializer(), original)
            contractJson.decodeFromString(PreparedPlayback.serializer(), encoded) shouldBe original
        }

        test("PreparedPlayback decodes without coverUrl field (backward compat)") {
            // A payload from an older server that predates coverUrl must still decode cleanly.
            val json =
                """{"bookId":"b1","audioFiles":[],"resumePosition":null}"""
            val decoded = contractJson.decodeFromString(PreparedPlayback.serializer(), json)
            decoded.bookId shouldBe "b1"
            decoded.coverUrl shouldBe null
        }

        test("RecordPositionRequest round-trips with currentChapterId null") {
            val original =
                RecordPositionRequest(
                    bookId = "book-1",
                    positionMs = 99_000L,
                    lastPlayedAt = 1_730_000_000_000L,
                    finished = false,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                )
            val encoded = contractJson.encodeToString(RecordPositionRequest.serializer(), original)
            contractJson.decodeFromString(RecordPositionRequest.serializer(), encoded) shouldBe original
        }

        test("RecordPositionRequest round-trips with currentChapterId non-null") {
            val original =
                RecordPositionRequest(
                    bookId = "book-2",
                    positionMs = 5_000L,
                    lastPlayedAt = 1_730_001_000_000L,
                    finished = true,
                    playbackSpeed = 1.5f,
                    currentChapterId = "chap-finale",
                )
            val encoded = contractJson.encodeToString(RecordPositionRequest.serializer(), original)
            contractJson.decodeFromString(RecordPositionRequest.serializer(), encoded) shouldBe original
        }
    })
