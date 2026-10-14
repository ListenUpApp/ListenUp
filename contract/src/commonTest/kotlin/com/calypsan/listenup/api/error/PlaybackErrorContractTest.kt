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

        val clientRaised: List<Triple<AppError, String, Boolean>> =
            listOf(
                Triple(PlaybackError.ConnectionLost(debugInfo = "d"), "PLAYBACK_CONNECTION_LOST", true),
                Triple(PlaybackError.ServerUnreachable(debugInfo = "d"), "PLAYBACK_SERVER_UNREACHABLE", true),
                Triple(PlaybackError.OfflineNotDownloaded(debugInfo = "d"), "PLAYBACK_OFFLINE_NOT_DOWNLOADED", false),
                Triple(PlaybackError.UnsupportedFormat(debugInfo = "d"), "PLAYBACK_UNSUPPORTED_FORMAT", false),
                Triple(PlaybackError.AutoplayBlocked(debugInfo = "d"), "PLAYBACK_AUTOPLAY_BLOCKED", false),
                Triple(PlaybackError.NoLongerAvailable(debugInfo = "d"), "PLAYBACK_NO_LONGER_AVAILABLE", false),
            )

        clientRaised.forEach { (error, code, retryable) ->
            test("$code round-trips with a stable SerialName and constant copy") {
                val json = contractJson.encodeToString(AppError.serializer(), error)
                json.contains("\"PlaybackError.${checkNotNull(error::class.simpleName)}\"") shouldBe true
                contractJson.decodeFromString(AppError.serializer(), json) shouldBe error
                error.code shouldBe code
                error.isRetryable shouldBe retryable
                error.message.endsWith(".") shouldBe true
                error.withCorrelationId("c").correlationId shouldBe "c"
            }
        }
    })
