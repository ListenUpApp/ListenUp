package com.calypsan.listenup.server.embeddedmeta

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp4File
import com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser
import com.calypsan.listenup.server.embeddedmeta.format.mp4.Mp4Parser
import com.calypsan.listenup.server.embeddedmeta.format.mp3.byteSource as mp3ByteSource
import com.calypsan.listenup.server.embeddedmeta.format.mp4.byteSource as mp4ByteSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Tests for the pre-computed loudness-gain tag parsers ([parseReplayGainDb] /
 * [parseITunNorm]) and their wiring into the MP3/MP4 format readers: a
 * `TXXX:REPLAYGAIN_TRACK_GAIN` frame or an `iTunNORM` freeform atom surfaces as
 * `AudioTags.normalizationGainDb`; a file with neither tag leaves it null.
 */
class GainTagParsingTest :
    FunSpec({

        context("parseReplayGainDb") {
            test("parses a standard '<value> dB' ReplayGain string") {
                parseReplayGainDb("-6.48 dB") shouldBe (-6.48f plusOrMinus 0.001f)
            }

            test("parses a positive gain with explicit plus sign") {
                parseReplayGainDb("+3.2 dB") shouldBe (3.2f plusOrMinus 0.001f)
            }

            test("parses a bare numeric value with no dB suffix") {
                parseReplayGainDb("2.5") shouldBe (2.5f plusOrMinus 0.001f)
            }

            test("returns null for a non-numeric value") {
                parseReplayGainDb("garbage").shouldBeNull()
            }

            test("returns null for an empty string") {
                parseReplayGainDb("").shouldBeNull()
            }

            test("clamps an implausibly large cut to -24 dB") {
                parseReplayGainDb("-99 dB") shouldBe (-24f plusOrMinus 0.001f)
            }
        }

        context("parseITunNorm") {
            test("averages the L/R Sound Check words into a dB gain") {
                // 0x000004D2 = 1234 per-mille of the 1000 base:
                // gain = -10 * log10(1234/1000) = -10 * 0.09131... = -0.9131... dB for each
                // channel; the L/R average of two identical words is the same value.
                parseITunNorm("000004D2 000004D2") shouldBe (-0.913f plusOrMinus 0.01f)
            }

            test("words at the 1000 base yield 0 dB") {
                // 0x000003E8 = 1000 → -10 * log10(1000/1000) = 0.
                parseITunNorm("000003E8 000003E8") shouldBe (0f plusOrMinus 0.001f)
            }

            test("returns null for a non-hex value") {
                parseITunNorm("garbage").shouldBeNull()
            }

            test("returns null for an empty string") {
                parseITunNorm("").shouldBeNull()
            }

            test("returns null for zero words (log10(0) is -Inf)") {
                parseITunNorm("00000000 00000000").shouldBeNull()
            }
        }

        context("format reader wiring") {
            test("MP3: TXXX REPLAYGAIN_TRACK_GAIN populates AudioTags.normalizationGainDb") {
                val bytes =
                    buildMp3File {
                        id3v2(version = 4) {
                            textFrame("TIT2", "Book")
                            txxxFrame("REPLAYGAIN_TRACK_GAIN", "-6.48 dB")
                        }
                        mpegFrames(durationSeconds = 1)
                    }
                val result = Mp3Parser().parse(mp3ByteSource(bytes))
                require(result is AppResult.Success<EmbeddedAudioMetadata>)
                result.data.tags.normalizationGainDb shouldBe (-6.48f plusOrMinus 0.001f)
            }

            test("MP4: iTunNORM freeform atom populates AudioTags.normalizationGainDb") {
                val bytes =
                    buildMp4File {
                        ftyp(brand = "M4B ")
                        moov {
                            mvhd(timescale = 1000, durationInTimescale = 60_000)
                            udta {
                                meta {
                                    tag("©nam", "Book")
                                    freeform(
                                        mean = "com.apple.iTunes",
                                        name = "iTunNORM",
                                        value =
                                            " 000004D2 000004D2 00000000 00000000" +
                                                " 00000000 00000000 00000000 00000000",
                                    )
                                }
                            }
                            audioTrack()
                        }
                    }
                val result = Mp4Parser().parse(mp4ByteSource(bytes))
                require(result is AppResult.Success<EmbeddedAudioMetadata>)
                result.data.tags.normalizationGainDb shouldBe (-0.913f plusOrMinus 0.01f)
            }

            test("MP3 with no gain tag leaves normalizationGainDb null") {
                val bytes =
                    buildMp3File {
                        id3v2(version = 4) { textFrame("TIT2", "Book") }
                        mpegFrames(durationSeconds = 1)
                    }
                val result = Mp3Parser().parse(mp3ByteSource(bytes))
                require(result is AppResult.Success<EmbeddedAudioMetadata>)
                result.data.tags.normalizationGainDb
                    .shouldBeNull()
            }
        }
    })
