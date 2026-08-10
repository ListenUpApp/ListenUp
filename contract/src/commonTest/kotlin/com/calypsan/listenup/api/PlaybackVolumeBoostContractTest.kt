package com.calypsan.listenup.api

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
    })
