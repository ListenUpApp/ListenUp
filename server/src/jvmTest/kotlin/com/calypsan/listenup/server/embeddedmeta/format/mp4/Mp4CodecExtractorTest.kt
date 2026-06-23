package com.calypsan.listenup.server.embeddedmeta.format.mp4

import com.calypsan.listenup.server.embeddedmeta.fixtures.ac4Entry
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildAudioMoov
import com.calypsan.listenup.server.embeddedmeta.fixtures.ec3Entry
import com.calypsan.listenup.server.embeddedmeta.fixtures.mp4aEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class Mp4CodecExtractorTest :
    FunSpec({
        fun extract(moovBytes: ByteArray) =
            Mp4CodecExtractor.extract(
                moovBytes,
                AtomWalker.findPath(moovBytes, "moov")!!,
            )

        test("mp4a + esds (xHE-AAC AOT 42, 48000/2, 256 kbps) yields aac/xhe with full specs") {
            val moov =
                buildAudioMoov(
                    mp4aEntry(
                        channels = 2,
                        sampleRate = 48000,
                        esdsAvgBitrate = 256_000,
                        ascAot = 42,
                        ascFreqIdx = 3,
                        ascChan = 2,
                    ),
                )

            extract(moov) shouldBe
                com.calypsan.listenup.domain.embeddedmeta.AudioStreamInfo(
                    codec = "aac",
                    codecProfile = "xhe",
                    spatial = null,
                    bitrate = 256_000,
                    sampleRate = 48000,
                    channels = 2,
                )
        }

        test("mp4a + esds (AAC-LC AOT 2) yields codecProfile lc") {
            val moov =
                buildAudioMoov(
                    mp4aEntry(
                        channels = 2,
                        sampleRate = 44100,
                        esdsAvgBitrate = 128_000,
                        ascAot = 2,
                        ascFreqIdx = 4,
                        ascChan = 2,
                    ),
                )

            val info = extract(moov)
            info?.codec shouldBe "aac"
            info?.codecProfile shouldBe "lc"
            info?.sampleRate shouldBe 44100
            info?.channels shouldBe 2
            info?.bitrate shouldBe 128_000
        }

        test("ac-4 entry yields ac4/atmos with entry-header specs") {
            val moov = buildAudioMoov(ac4Entry(channels = 6, sampleRate = 48000))

            val info = extract(moov)
            info?.codec shouldBe "ac4"
            info?.spatial shouldBe "atmos"
            info?.sampleRate shouldBe 48000
            info?.channels shouldBe 6
            info?.bitrate.shouldBeNull()
        }

        test("ec-3 entry with JOC yields eac3/atmos") {
            val moov = buildAudioMoov(ec3Entry(channels = 6, sampleRate = 48000, joc = true))

            val info = extract(moov)
            info?.codec shouldBe "eac3"
            info?.spatial shouldBe "atmos"
            info?.sampleRate shouldBe 48000
            info?.channels shouldBe 6
        }

        test("ec-3 entry without JOC yields eac3 and no spatial flag") {
            val moov = buildAudioMoov(ec3Entry(channels = 6, sampleRate = 48000, joc = false))

            val info = extract(moov)
            info?.codec shouldBe "eac3"
            info?.spatial.shouldBeNull()
        }

        test("moov with no audio (soun) track yields null") {
            // A moov carrying only an mvhd — no trak at all.
            val moov = buildAudioMoov(audioEntry = null)

            extract(moov) shouldBe null
        }

        test("buildAudioMoov produces a self-consistent, walkable moov") {
            val moov = buildAudioMoov(mp4aEntry(channels = 2, sampleRate = 44100))
            AtomWalker.findPath(moov, "moov") shouldNotBe null
        }
    })
