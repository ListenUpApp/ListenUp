package com.calypsan.listenup.server.embeddedmeta.fixtures

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class BuildMp3FileTest :
    FunSpec({
        test("buildMp3File with id3v2 + one MPEG frame produces nonzero output starting with ID3") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "The Way of Kings")
                        textFrame("TPE1", "Brandon Sanderson")
                    }
                    mpegFrames(durationSeconds = 1, bitrate = 64_000)
                }
            bytes.size shouldNotBe 0
            // ID3 magic at start
            listOf(bytes[0], bytes[1], bytes[2]) shouldBe listOf<Byte>(0x49, 0x44, 0x33)
        }

        test("buildMp3File with id3v2 v3 emits big-endian frame sizes") {
            val bytes =
                buildMp3File {
                    id3v2(version = 3) {
                        textFrame("TIT2", "Title")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            // Header is 10 bytes; first frame begins at byte 10 with id "TIT2"
            listOf(bytes[10], bytes[11], bytes[12], bytes[13]) shouldBe listOf<Byte>(0x54, 0x49, 0x54, 0x32)
        }

        test("buildMp3File without id3v2 still emits an MPEG frame block") {
            val bytes =
                buildMp3File {
                    mpegFrames(durationSeconds = 1)
                }
            // First byte is 0xFF (MPEG sync)
            bytes[0] shouldBe 0xFF.toByte()
        }

        test("buildMp3File can append id3v1 footer (last 128 bytes start with TAG)") {
            val bytes =
                buildMp3File {
                    mpegFrames(durationSeconds = 1)
                    id3v1(title = "Hi", artist = "Me", album = "Album")
                }
            val tagOffset = bytes.size - 128
            listOf(bytes[tagOffset], bytes[tagOffset + 1], bytes[tagOffset + 2]) shouldBe listOf<Byte>(0x54, 0x41, 0x47)
        }

        // The parser fix these fixtures pin is only as trustworthy as the fixtures: a builder bug
        // and a decoder bug can cancel out and leave the suite green on wrong behaviour. So decode
        // the emitted header back out here, independently of MpegDurationCalculator.
        test("mpegFrames emits the requested version, layer, bitrate index and sample-rate index") {
            data class Case(
                val version: MpegVersion,
                val bitrate: Int,
                val sampleRate: Int,
                val expectedBitrateIdx: Int,
                val expectedSampleRateIdx: Int,
            )
            val cases =
                listOf(
                    Case(MpegVersion.V1, 128_000, 44_100, expectedBitrateIdx = 9, expectedSampleRateIdx = 0),
                    Case(MpegVersion.V2, 64_000, 22_050, expectedBitrateIdx = 8, expectedSampleRateIdx = 0),
                    Case(MpegVersion.V2_5, 32_000, 11_025, expectedBitrateIdx = 4, expectedSampleRateIdx = 0),
                    Case(MpegVersion.V2, 16_000, 24_000, expectedBitrateIdx = 2, expectedSampleRateIdx = 1),
                    Case(MpegVersion.V2_5, 8_000, 8_000, expectedBitrateIdx = 1, expectedSampleRateIdx = 2),
                )
            for (case in cases) {
                val header =
                    decodeFrameHeader(
                        buildMp3File {
                            mpegFrames(
                                durationSeconds = 1,
                                bitrate = case.bitrate,
                                sampleRate = case.sampleRate,
                                version = case.version,
                            )
                        },
                    )
                withClue(case.toString()) {
                    header.syncBits shouldBe 0x7FF
                    header.versionBits shouldBe case.version.versionBits
                    header.layerBits shouldBe 0b01
                    header.bitrateIdx shouldBe case.expectedBitrateIdx
                    header.sampleRateIdx shouldBe case.expectedSampleRateIdx
                    header.channelModeBits shouldBe 0b00
                }
            }
        }

        test("mono frames carry channel mode 0b11; stereo frames carry 0b00") {
            val monoHeader =
                decodeFrameHeader(
                    buildMp3File {
                        mpegFrames(durationSeconds = 1, bitrate = 32_000, sampleRate = 22_050, version = MpegVersion.V2, mono = true)
                    },
                )
            monoHeader.channelModeBits shouldBe 0b11

            val stereoHeader =
                decodeFrameHeader(
                    buildMp3File {
                        mpegFrames(durationSeconds = 1, bitrate = 32_000, sampleRate = 22_050, version = MpegVersion.V2)
                    },
                )
            stereoHeader.channelModeBits shouldBe 0b00
        }

        test("the Xing tag lands immediately past the version- and channel-dependent side info") {
            // MPEG-1 stereo → side info 32 → Xing at 36; MPEG-2 stereo → 17 → 21; MPEG-2 mono → 9 → 13.
            val expectations =
                listOf(
                    Triple(MpegVersion.V1, false, 36),
                    Triple(MpegVersion.V2, false, 21),
                    Triple(MpegVersion.V2, true, 13),
                )
            for ((version, mono, expectedOffset) in expectations) {
                val bytes =
                    buildMp3File {
                        xingVbrFrames(
                            frameCount = 100,
                            sampleRate = if (version == MpegVersion.V1) 44_100 else 22_050,
                            bitrate = if (version == MpegVersion.V1) 128_000 else 64_000,
                            version = version,
                            mono = mono,
                        )
                    }
                withClue("$version mono=$mono") {
                    4 + sideInfoSize(version, mono) shouldBe expectedOffset
                    String(bytes, expectedOffset, 4, Charsets.ISO_8859_1) shouldBe "Xing"
                }
            }
        }
    })

/** The frame-header fields this spec asserts, decoded straight out of the emitted bytes. */
private data class DecodedFrameHeader(
    val syncBits: Int,
    val versionBits: Int,
    val layerBits: Int,
    val bitrateIdx: Int,
    val sampleRateIdx: Int,
    val channelModeBits: Int,
)

/** Decode the first four bytes of [bytes] as an MPEG frame header. */
private fun decodeFrameHeader(bytes: ByteArray): DecodedFrameHeader {
    val header =
        ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
    return DecodedFrameHeader(
        syncBits = (header ushr 21) and 0x7FF,
        versionBits = (header ushr 19) and 0x3,
        layerBits = (header ushr 17) and 0x3,
        bitrateIdx = (header ushr 12) and 0xF,
        sampleRateIdx = (header ushr 10) and 0x3,
        channelModeBits = (header ushr 6) and 0x3,
    )
}
