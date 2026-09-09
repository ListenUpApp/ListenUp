package com.calypsan.listenup.server.embeddedmeta.format.mp3

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.server.embeddedmeta.fixtures.MpegVersion
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies that [MpegDurationCalculator] (via [Mp3Parser]) decodes MPEG-2 (LSF)
 * and MPEG-2.5 frames with their own tables, not MPEG-1's.
 *
 * Four facts from ISO/IEC 11172-3 (MPEG-1) and ISO/IEC 13818-3 (MPEG-2 LSF),
 * with MPEG-2.5 the de-facto Fraunhofer extension of the latter:
 *
 * 1. **Version bits** (header bits 20..19): `0b11` MPEG-1, `0b10` MPEG-2,
 *    `0b01` reserved, `0b00` MPEG-2.5.
 * 2. **Layer III bitrate table**, kbps, indices 1..14 — index 0 is "free" and
 *    15 is "bad", both invalid:
 *    - MPEG-1: 32 40 48 56 64 80 96 112 128 160 192 224 256 320
 *    - MPEG-2 / 2.5: 8 16 24 32 40 48 56 64 80 96 112 128 144 160
 * 3. **Sample-rate table**, Hz, indices 0..2 — index 3 is reserved:
 *    - MPEG-1: 44100 48000 32000
 *    - MPEG-2: 22050 24000 16000 (exactly half)
 *    - MPEG-2.5: 11025 12000 8000 (exactly a quarter)
 * 4. **Samples per frame, Layer III**: 1152 on MPEG-1; 576 on MPEG-2 and
 *    MPEG-2.5 — the "low sampling frequency" halving.
 *
 * VBR duration is therefore `frameCount × samplesPerFrame × 1000 ÷ sampleRate`,
 * and the CBR fallback is `audioBytes × 8 × 1000 ÷ bitrate`. Note that on
 * MPEG-2 the VBR arithmetic is *coincidentally* right even with MPEG-1 tables —
 * the sample rate reads 2× high and samples-per-frame is used 2× high, and the
 * two errors cancel — which is why those cases also assert the reported stream
 * facts, where the error is visible.
 */
class MpegVersionDurationTest :
    FunSpec({
        val parser = Mp3Parser()

        /** The exact VBR duration the parser must return for these frame-geometry inputs. */
        fun expectedVbrMs(
            frameCount: Int,
            samplesPerFrame: Int,
            sampleRate: Int,
        ): Long = frameCount.toLong() * samplesPerFrame.toLong() * 1_000L / sampleRate

        /** ±2% of [expectedMs] — the tolerance the CBR byte-count estimate is held to. */
        fun withinTwoPercent(expectedMs: Long): LongRange = (expectedMs * 98 / 100)..(expectedMs * 102 / 100)

        test("MPEG-2 CBR at 22.05 kHz mono, 32 kbps: duration, bitrate and sample rate are the file's own") {
            val bytes =
                buildMp3File {
                    mpegFrames(
                        durationSeconds = 60,
                        bitrate = 32_000,
                        sampleRate = 22_050,
                        version = MpegVersion.V2,
                        mono = true,
                    )
                }

            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)

            withClue("durationMs=${result.data.durationMs}") {
                (result.data.durationMs in withinTwoPercent(60_000L)) shouldBe true
            }
            result.data.audioStream?.bitrate shouldBe 32_000
            result.data.audioStream?.sampleRate shouldBe 22_050
            result.data.audioStream?.channels shouldBe 1
        }

        test("MPEG-2 Xing at 22.05 kHz: duration uses 576 samples/frame and the file's real sample rate") {
            val frameCount = 1_000
            val bytes =
                buildMp3File {
                    xingVbrFrames(
                        frameCount = frameCount,
                        sampleRate = 22_050,
                        bitrate = 64_000,
                        version = MpegVersion.V2,
                    )
                }

            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)

            result.data.durationMs shouldBe expectedVbrMs(frameCount, samplesPerFrame = 576, sampleRate = 22_050)
            result.data.audioStream?.sampleRate shouldBe 22_050
            result.data.audioStream?.bitrate shouldBe 64_000
        }

        test("MPEG-2.5 Xing at 11.025 kHz: duration is the full length, not half") {
            val frameCount = 1_000
            val bytes =
                buildMp3File {
                    xingVbrFrames(
                        frameCount = frameCount,
                        sampleRate = 11_025,
                        bitrate = 64_000,
                        version = MpegVersion.V2_5,
                    )
                }

            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)

            result.data.durationMs shouldBe expectedVbrMs(frameCount, samplesPerFrame = 576, sampleRate = 11_025)
            result.data.audioStream?.sampleRate shouldBe 11_025
            result.data.audioStream?.bitrate shouldBe 64_000
        }

        test("MPEG-2.5 CBR at 11.025 kHz, 32 kbps: duration and stream facts are the file's own") {
            val bytes =
                buildMp3File {
                    mpegFrames(
                        durationSeconds = 60,
                        bitrate = 32_000,
                        sampleRate = 11_025,
                        version = MpegVersion.V2_5,
                    )
                }

            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)

            withClue("durationMs=${result.data.durationMs}") {
                (result.data.durationMs in withinTwoPercent(60_000L)) shouldBe true
            }
            result.data.audioStream?.bitrate shouldBe 32_000
            result.data.audioStream?.sampleRate shouldBe 11_025
        }

        test("MPEG-2 VBRI at 22.05 kHz: duration uses 576 samples/frame and the file's real sample rate") {
            val frameCount = 2_000
            val bytes =
                buildMp3File {
                    vbriVbrFrames(
                        frameCount = frameCount,
                        sampleRate = 22_050,
                        bitrate = 64_000,
                        version = MpegVersion.V2,
                    )
                }

            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)

            result.data.durationMs shouldBe expectedVbrMs(frameCount, samplesPerFrame = 576, sampleRate = 22_050)
            result.data.audioStream?.sampleRate shouldBe 22_050
            result.data.audioStream?.bitrate shouldBe 64_000
        }

        test("MPEG-1 control: 44.1 kHz Xing answers are byte-identical to before the LSF tables landed") {
            val frameCount = 1_000
            val bytes =
                buildMp3File {
                    xingVbrFrames(
                        frameCount = frameCount,
                        sampleRate = 44_100,
                        bitrate = 128_000,
                        version = MpegVersion.V1,
                    )
                }

            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)

            result.data.durationMs shouldBe expectedVbrMs(frameCount, samplesPerFrame = 1_152, sampleRate = 44_100)
            result.data.audioStream?.sampleRate shouldBe 44_100
            result.data.audioStream?.bitrate shouldBe 128_000
            result.data.audioStream?.channels shouldBe 2
        }

        // Layer bits (18..17): 0b01 Layer III, 0b10 Layer II, 0b11 Layer I, 0b00 reserved.
        // Only Layer III matches this calculator's tables — everything else must decline rather
        // than produce a plausible-looking wrong duration. Declining is not rejecting the file:
        // the parser still surfaces its tags, only the duration is reported as unknown.
        listOf(
            "Layer II" to 0b10,
            "Layer I" to 0b11,
            "the reserved layer" to 0b00,
        ).forEach { (label, layerBits) ->
            test("a frame declaring $label yields no duration, and the tags still parse") {
                val bytes =
                    buildMp3File {
                        id3v2(version = 4) { textFrame("TIT2", "Not Layer Three") }
                        mpegFrames(durationSeconds = 10, layerBits = layerBits)
                    }

                val result = parser.parse(byteSource(bytes))
                require(result is AppResult.Success<EmbeddedAudioMetadata>)

                result.data.durationMs shouldBe 0L
                result.data.audioStream?.bitrate shouldBe null
                result.data.audioStream?.sampleRate shouldBe null
                result.data.audioStream?.channels shouldBe null
                result.data.tags.title shouldBe "Not Layer Three"
            }
        }

        test("a frame declaring the reserved MPEG version yields no duration, and the tags still parse") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) { textFrame("TIT2", "Reserved Version") }
                    mpegFrames(
                        durationSeconds = 10,
                        bitrate = 32_000,
                        sampleRate = 22_050,
                        version = MpegVersion.RESERVED,
                    )
                }

            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)

            result.data.durationMs shouldBe 0L
            result.data.audioStream?.bitrate shouldBe null
            result.data.audioStream?.sampleRate shouldBe null
            result.data.audioStream?.channels shouldBe null
            result.data.tags.title shouldBe "Reserved Version"
        }
    })
