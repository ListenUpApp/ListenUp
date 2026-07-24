package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PlaybackPositionPayloadContractTest :
    FunSpec({

        test("PlaybackPositionSyncPayload round-trips with deletedAt = null") {
            val original =
                PlaybackPositionSyncPayload(
                    id = "pos-1",
                    bookId = "book-1",
                    positionMs = 42_000L,
                    lastPlayedAt = 1_730_000_000_000L,
                    finished = false,
                    playbackSpeed = 1.0f,
                    currentChapterId = "chap-1",
                    revision = 3L,
                    updatedAt = 1_730_000_000_000L,
                    createdAt = 1_720_000_000_000L,
                    deletedAt = null,
                )
            val encoded = contractJson.encodeToString(PlaybackPositionSyncPayload.serializer(), original)
            contractJson.decodeFromString(PlaybackPositionSyncPayload.serializer(), encoded) shouldBe original
        }

        test("PlaybackPositionSyncPayload round-trips with deletedAt non-null") {
            val original =
                PlaybackPositionSyncPayload(
                    id = "pos-2",
                    bookId = "book-2",
                    positionMs = 0L,
                    lastPlayedAt = 1_730_000_500_000L,
                    finished = true,
                    playbackSpeed = 1.25f,
                    currentChapterId = null,
                    revision = 9L,
                    updatedAt = 1_730_000_500_000L,
                    createdAt = 1_720_000_000_000L,
                    deletedAt = 1_730_001_000_000L,
                )
            val encoded = contractJson.encodeToString(PlaybackPositionSyncPayload.serializer(), original)
            contractJson.decodeFromString(PlaybackPositionSyncPayload.serializer(), encoded) shouldBe original
        }
    })
