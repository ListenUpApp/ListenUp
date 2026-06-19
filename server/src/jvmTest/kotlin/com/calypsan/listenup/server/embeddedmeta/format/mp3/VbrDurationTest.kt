package com.calypsan.listenup.server.embeddedmeta.format.mp3

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Verifies that [MpegDurationCalculator] (via [Mp3Parser]) correctly computes
 * duration from Xing/Info and VBRI VBR headers, and that the CBR fallback is
 * preserved when no VBR header is present.
 *
 * Duration formula for VBR: frameCount × 1152 samples/frame × 1000 ms/s ÷ sampleRate Hz.
 *
 * Example at 44 100 Hz with 1 000 declared frames:
 *   1 000 × 1 152 × 1 000 / 44 100 ≈ 26 122 ms  (≈ 26.1 s)
 */
class VbrDurationTest :
    FunSpec({
        val parser = Mp3Parser()

        /**
         * Compute the exact VBR duration in milliseconds that the parser should
         * return for [frameCount] frames at [sampleRate] Hz.
         */
        fun expectedVbrMs(
            frameCount: Int,
            sampleRate: Int = 44_100,
        ): Long = frameCount.toLong() * 1_152L * 1_000L / sampleRate

        test("Xing VBR header: parser returns frame-count-derived duration, not CBR estimate") {
            // 1 000 frames at 44 100 Hz → VBR duration ≈ 26 122 ms.
            // Extra 500 KB of padding inflates the CBR estimate to ≈ 31 276 ms,
            // so the two formulas produce measurably different answers — proving
            // that the parser took the VBR path when it returns the exact value.
            val frameCount = 1_000
            val extraPadding = 500_000
            val bytes =
                buildMp3File {
                    xingVbrFrames(
                        frameCount = frameCount,
                        tag = "Xing",
                        sampleRate = 44_100,
                        bitrate = 128_000,
                        extraPaddingBytes = extraPadding,
                    )
                }

            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)

            val vbrExpected = expectedVbrMs(frameCount, 44_100)
            val cbrEstimate = bytes.size.toLong() * 8L * 1_000L / 128_000L

            // CBR formula yields a larger number due to extra padding bytes.
            (cbrEstimate > vbrExpected) shouldBe true

            // Parser must have taken the VBR path — returns the exact frame-count value.
            result.data.durationMs shouldBe vbrExpected
        }

        test("Info VBR header (CBR-with-header): parser returns frame-count-derived duration") {
            val frameCount = 500
            val bytes =
                buildMp3File {
                    xingVbrFrames(
                        frameCount = frameCount,
                        tag = "Info",
                        sampleRate = 44_100,
                        bitrate = 64_000,
                    )
                }

            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)

            result.data.durationMs shouldBe expectedVbrMs(frameCount, 44_100)
        }

        test("VBRI header: parser returns frame-count-derived duration") {
            val frameCount = 2_000
            val bytes =
                buildMp3File {
                    vbriVbrFrames(
                        frameCount = frameCount,
                        sampleRate = 44_100,
                        bitrate = 128_000,
                    )
                }

            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)

            result.data.durationMs shouldBe expectedVbrMs(frameCount, 44_100)
        }

        test("CBR file without VBR header: CBR formula is used, not VBR") {
            // A genuine CBR file produced by mpegFrames() has no Xing/VBRI header.
            // The parser must fall back to the byte-count estimate.
            val bytes =
                buildMp3File {
                    mpegFrames(durationSeconds = 10, bitrate = 64_000, sampleRate = 44_100)
                }

            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)

            // Duration should be in the right ballpark (within ±2 s of 10 s).
            result.data.durationMs shouldNotBe 0L
            result.data.durationMs shouldBeGreaterThan 0L
            (result.data.durationMs in 8_000L..12_000L) shouldBe true
        }

        test("file with no valid MPEG frame returns durationMs = 0 (failure path intact)") {
            // A file that is just an ID3v2 tag with no MPEG audio frames.
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "No Audio")
                    }
                    // No mpegFrames/xingVbrFrames call — no audio body.
                }

            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.durationMs shouldBe 0L
        }
    })
