package com.calypsan.listenup.api.error

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith

class TranscodeErrorTest :
    FunSpec({

        // isRetryable is a strict contract: true only when retry middleware can blindly re-fire.
        test("busy is retryable and the others are not") {
            TranscodeError.TranscoderBusy().isRetryable shouldBe true
            TranscodeError.TranscoderUnavailable().isRetryable shouldBe false
            TranscodeError.TranscodeFailed().isRetryable shouldBe false
        }

        test("messages are user-facing sentences") {
            listOf(
                TranscodeError.TranscoderBusy(),
                TranscodeError.TranscoderUnavailable(),
                TranscodeError.TranscodeFailed(),
            ).forEach {
                it.message shouldEndWith "."
            }
        }

        test("codes are stable") {
            TranscodeError.TranscoderBusy().code shouldBe "TRANSCODE_BUSY"
            TranscodeError.TranscoderUnavailable().code shouldBe "TRANSCODE_UNAVAILABLE"
            TranscodeError.TranscodeFailed().code shouldBe "TRANSCODE_FAILED"
        }

        // Every family must be stampable, or a correlation id is lost on one transport and not the
        // other — the exact drift CorrelationStamping exists to make impossible.
        test("a correlation id can be stamped onto every variant") {
            val stamped =
                listOf(
                    TranscodeError.TranscoderBusy(),
                    TranscodeError.TranscoderUnavailable(),
                    TranscodeError.TranscodeFailed(debugInfo = "ffmpeg: no such file"),
                ).map { (it as AppError).withCorrelationId("req-1") }

            stamped.forEach { it.correlationId shouldBe "req-1" }
        }

        // The stderr tail is why TranscodeFailed carries debugInfo at all: an operator needs to know
        // what FFmpeg said, and the listener must never be shown it.
        test("stamping preserves the ffmpeg stderr tail") {
            val failed = TranscodeError.TranscodeFailed(debugInfo = "Invalid data found")

            val stamped = (failed as AppError).withCorrelationId("req-2")

            stamped.debugInfo shouldBe "Invalid data found"
        }
    })
