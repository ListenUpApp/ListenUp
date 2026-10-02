package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins that [PlaybackError.Stalled] round-trips through the canonical [contractJson]
 * using the polymorphic `AppError.serializer()` and has a stable `@SerialName`,
 * plus the body-level constants the UI consumes.
 */
class PlaybackErrorContractTest :
    FunSpec({
        test("Stalled round-trips through JSON") {
            val original: AppError =
                PlaybackError.Stalled(
                    correlationId = "abc-123",
                    debugInfo = "buffering exceeded 10 minutes",
                )
            val encoded = contractJson.encodeToString(AppError.serializer(), original)
            val decoded = contractJson.decodeFromString(AppError.serializer(), encoded)
            decoded shouldBe original
        }

        test("Stalled has stable @SerialName") {
            val err: AppError = PlaybackError.Stalled()
            val json = contractJson.encodeToString(AppError.serializer(), err)
            json.contains(""""type":"PlaybackError.Stalled"""") shouldBe true
        }

        test("Stalled exposes the expected constants") {
            val error = PlaybackError.Stalled()
            error.message shouldBe "Playback stalled. Tap to retry."
            error.code shouldBe "PLAYBACK_STALLED"
            error.isRetryable shouldBe true
        }

        test("should round-trip PlaybackError.CouldNotStart through AppError serializer") {
            val original: AppError = PlaybackError.CouldNotStart(debugInfo = "bookId=b1")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            json.contains("\"PlaybackError.CouldNotStart\"") shouldBe true
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
            PlaybackError.CouldNotStart().code shouldBe "PLAYBACK_COULD_NOT_START"
            PlaybackError.CouldNotStart().isRetryable shouldBe false
        }
    })
