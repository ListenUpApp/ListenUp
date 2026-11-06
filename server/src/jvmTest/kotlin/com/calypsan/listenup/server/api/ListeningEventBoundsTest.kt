package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private const val NOW = 1_800_000_000_000L

private val valid =
    RecordListeningEventRequest(
        id = "e",
        bookId = "b",
        startPositionMs = 0L,
        endPositionMs = 60_000L,
        startedAt = NOW - 120_000L,
        endedAt = NOW - 60_000L,
        playbackSpeed = 1.0f,
        tz = "UTC",
        deviceLabel = null,
    )

class ListeningEventBoundsTest :
    FunSpec({
        fun RecordListeningEventRequest.field() = boundsViolation(NOW).shouldNotBeNull().field

        test("an ordinary span is accepted") { valid.boundsViolation(NOW).shouldBeNull() }

        test("ending before starting is refused") {
            valid.copy(endedAt = valid.startedAt - 1L).field() shouldBe "endedAt"
        }
        test("a span over 24 hours is refused, exactly 24 hours is accepted") {
            valid.copy(endedAt = valid.startedAt + MAX_SPAN_MS + 1L).field() shouldBe "endedAt"
            valid.copy(endedAt = valid.startedAt + MAX_SPAN_MS).boundsViolation(NOW).shouldBeNull()
        }
        test("ending over 24 hours ahead of now is refused, exactly now plus 24 hours is accepted") {
            val limit = NOW + MAX_FUTURE_SKEW_MS
            valid.copy(startedAt = limit - 1_000L, endedAt = limit + 1L).field() shouldBe "endedAt"
            valid.copy(startedAt = limit - 1_000L, endedAt = limit).boundsViolation(NOW).shouldBeNull()
        }
        test("a negative start time is refused") { valid.copy(startedAt = -1L, endedAt = 0L).field() shouldBe "startedAt" }
        test("negative positions are refused, zero is accepted") {
            valid.copy(startPositionMs = -1L).field() shouldBe "startPositionMs"
            valid.copy(endPositionMs = -1L).field() shouldBe "endPositionMs"
            valid.copy(startPositionMs = 0L, endPositionMs = 0L).boundsViolation(NOW).shouldBeNull()
        }
        test("speeds of zero, below zero, above four and NaN are refused, exactly four is accepted") {
            valid.copy(playbackSpeed = 0f).field() shouldBe "playbackSpeed"
            valid.copy(playbackSpeed = -1f).field() shouldBe "playbackSpeed"
            valid.copy(playbackSpeed = 4.01f).field() shouldBe "playbackSpeed"
            valid.copy(playbackSpeed = Float.NaN).field() shouldBe "playbackSpeed"
            valid.copy(playbackSpeed = MAX_PLAYBACK_SPEED).boundsViolation(NOW).shouldBeNull()
        }
        test("an over-long timezone or device label is refused, the limits themselves are accepted") {
            valid.copy(tz = "x".repeat(MAX_TZ_LENGTH + 1)).field() shouldBe "tz"
            valid.copy(deviceLabel = "x".repeat(MAX_DEVICE_LABEL_LENGTH + 1)).field() shouldBe "deviceLabel"
            valid
                .copy(tz = "x".repeat(MAX_TZ_LENGTH), deviceLabel = "x".repeat(MAX_DEVICE_LABEL_LENGTH))
                .boundsViolation(NOW)
                .shouldBeNull()
        }
    })
