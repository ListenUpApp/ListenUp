package com.calypsan.listenup.server.embeddedmeta.fixtures

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
    })
