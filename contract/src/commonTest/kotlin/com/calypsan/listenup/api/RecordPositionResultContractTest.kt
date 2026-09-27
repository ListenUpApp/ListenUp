package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.RecordPositionResult
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString

class RecordPositionResultContractTest :
    FunSpec({
        test("RecordPositionResult round-trips through JSON") {
            val result =
                RecordPositionResult(
                    position =
                        PlaybackPositionSyncPayload(
                            id = "p1",
                            bookId = "b1",
                            positionMs = 10L,
                            lastPlayedAt = 20L,
                            finished = false,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                            revision = 3L,
                            updatedAt = 0L,
                            createdAt = 0L,
                            deletedAt = null,
                        ),
                    accepted = false,
                    serverNowMs = 99L,
                )
            val json = contractJson.encodeToString(result)
            contractJson.decodeFromString<RecordPositionResult>(json) shouldBe result
        }
    })
