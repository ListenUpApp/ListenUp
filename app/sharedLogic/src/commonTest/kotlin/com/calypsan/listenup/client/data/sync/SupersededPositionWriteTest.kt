package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.dto.RecordPositionResult
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain

class SupersededPositionWriteTest :
    FunSpec({
        val request =
            RecordPositionRequest(
                bookId = "b1",
                positionMs = 100_000L,
                lastPlayedAt = 1_000L,
                finished = true,
                playbackSpeed = 1.0f,
                currentChapterId = null,
            )
        val stored =
            PlaybackPositionSyncPayload(
                id = "p1",
                bookId = "b1",
                positionMs = 50_000L,
                lastPlayedAt = 2_000L,
                finished = false,
                playbackSpeed = 1.0f,
                currentChapterId = null,
                revision = 1L,
                updatedAt = 0L,
                createdAt = 0L,
                deletedAt = null,
            )

        test("a landed write says nothing") {
            describeSupersededPositionWrite(
                request,
                RecordPositionResult(position = stored, accepted = true, serverNowMs = 0L),
                deviceNowMs = 0L,
            ).shouldBeNull()
        }

        test("a superseded write names both rows and the device's measured clock offset") {
            val line =
                describeSupersededPositionWrite(
                    request,
                    RecordPositionResult(position = stored, accepted = false, serverNowMs = 700_000L),
                    deviceNowMs = 100_000L,
                ).shouldNotBeNull()

            line shouldContain "book=b1"
            line shouldContain "finished=true"
            line shouldContain "server kept lastPlayedAt=2000 pos=50000"
            line shouldContain "deviceClockOffsetMs=600000"
        }
    })
