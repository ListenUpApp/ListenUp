package com.calypsan.listenup.server.embeddedmeta.format.mp3

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.AudioTags
import com.calypsan.listenup.domain.embeddedmeta.ChapterSource
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.domain.embeddedmeta.SeriesEntry
import com.calypsan.listenup.server.io.ByteArraySeekableSource
import com.calypsan.listenup.server.io.SeekableSource
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException

class Mp3ParserTest :
    FunSpec({
        val parser = Mp3Parser()

        test("supports = setOf(AudioFormat.Mp3)") {
            parser.supports shouldBe setOf(AudioFormat.Mp3)
        }

        test("parse returns Success with title and artist from ID3v2.4 text frames") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "The Way of Kings")
                        textFrame("TPE1", "Brandon Sanderson")
                        textFrame("TALB", "Stormlight Archive")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.format shouldBe AudioFormat.Mp3
            result.data.tags.title shouldBe "The Way of Kings"
            result.data.tags.authors shouldBe listOf("Brandon Sanderson")
        }

        test("parse returns Success with title from ID3v2.3 text frames") {
            val bytes =
                buildMp3File {
                    id3v2(version = 3) {
                        textFrame("TIT2", "Mistborn")
                        textFrame("TPE1", "Brandon Sanderson")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.title shouldBe "Mistborn"
        }

        test("parse extracts CHAP frames into chapters list") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "Book")
                        chapFrame("ch1", startMs = 0, endMs = 30_000, title = "Chapter One")
                        chapFrame("ch2", startMs = 30_000, endMs = 60_000, title = "Chapter Two")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.chapters shouldHaveSize 2
            result.data.chaptersSource shouldBe ChapterSource.Id3v2Chap
            result.data.chapters[0].title shouldBe "Chapter One"
            result.data.chapters[0].startMs shouldBe 0L
            result.data.chapters[0].endMs shouldBe 30_000L
            result.data.chapters[1].title shouldBe "Chapter Two"
        }

        test("parse extracts APIC artwork (front cover, picture type 3)") {
            val fakePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "Book")
                        apicFrame(mime = "image/png", pictureType = 3, description = "Cover", imageBytes = fakePng)
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.artwork?.mime shouldBe "image/png"
            result.data.artwork
                ?.bytes
                ?.toList() shouldBe fakePng.toList()
        }

        test("parse extracts COMM comment frame into tags.custom[COMMENT_KEY]") {
            val bytes =
                buildMp3File {
                    id3v2 { commFrame(text = "A sweeping epic.") }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.custom[AudioTags.COMMENT_KEY] shouldBe "A sweeping epic."
        }

        test("parse keeps the first COMM frame when multiple are present") {
            val bytes =
                buildMp3File {
                    id3v2 {
                        commFrame(text = "First.")
                        commFrame(text = "Second.")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.custom[AudioTags.COMMENT_KEY] shouldBe "First."
        }

        test("parse extracts TXXX user-defined frames into tags.custom") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "Book")
                        txxxFrame("CUSTOM_KEY", "custom-value")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.custom["CUSTOM_KEY"] shouldBe "custom-value"
        }

        test("parse maps TDES description frame to tags.description") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "Book")
                        textFrame("TDES", "A sweeping epic of stone and storm.")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.description shouldBe "A sweeping epic of stone and storm."
        }

        test("parse falls back to ID3v1 footer when no ID3v2") {
            val bytes =
                buildMp3File {
                    mpegFrames(durationSeconds = 1)
                    id3v1(title = "Old Title", artist = "Old Artist", album = "Old Album")
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.title shouldBe "Old Title"
            result.data.tags.authors shouldBe listOf("Old Artist")
        }

        test("parse computes nonzero durationMs from MPEG frames (CBR)") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) { textFrame("TIT2", "Book") }
                    mpegFrames(durationSeconds = 5, bitrate = 64_000)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            // Allow ±1s tolerance — duration is approximated from frame count.
            (result.data.durationMs in 4_000..6_000) shouldBe true
        }

        test("TXXX:Subtitle populates subtitle when no TIT3 is present") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "The Eye of the World")
                        txxxFrame("subtitle", "A Tale of Two Tags")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.subtitle shouldBe "A Tale of Two Tags"
        }

        test("explicit TIT3 subtitle is not clobbered by a later TXXX:Subtitle") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "The Eye of the World")
                        textFrame("TIT3", "Primary")
                        txxxFrame("subtitle", "Secondary")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.subtitle shouldBe "Primary"
        }

        test("TSOT and TSOP populate sort fields") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "The Way of Kings")
                        textFrame("TSOT", "Way of Kings, The")
                        textFrame("TSOP", "Sanderson, Brandon")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.titleSort shouldBe "Way of Kings, The"
            result.data.tags.authorsSort shouldBe "Sanderson, Brandon"
        }

        test("parse maps IO failure to AudioMetadataError.IoError") {
            val source =
                object : SeekableSource {
                    override val length: Long = 100

                    override fun position(): Long = 0

                    override fun seek(offset: Long) {}

                    override fun read(
                        into: ByteArray,
                        count: Int,
                    ): Int = -1

                    override fun readFully(count: Int): ByteArray = throw IOException("boom")

                    override fun close() {}
                }
            val result = parser.parse(source)
            require(result is AppResult.Failure)
            (result.error is com.calypsan.listenup.api.error.AudioMetadataError.IoError) shouldBe true
        }

        test("semicolon series and series-part tags zip into multiple series") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "Words of Radiance")
                        textFrame("MVNM", "Cosmere;Stormlight")
                        textFrame("MVIN", "3;4")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.series shouldBe listOf(SeriesEntry("Cosmere", "3"), SeriesEntry("Stormlight", "4"))
        }

        test("GRP1 grouping is used as series when no dedicated series tag") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "Book")
                        textFrame("GRP1", "Wheel of Time #3")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.series shouldBe listOf(SeriesEntry("Wheel of Time", "3"))
        }

        test("a dedicated series tag wins over GRP1 grouping") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "Book")
                        textFrame("MVNM", "Real Series")
                        textFrame("MVIN", "2")
                        textFrame("GRP1", "Grouping Series #9")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.series shouldBe listOf(SeriesEntry("Real Series", "2"))
        }

        test("audioStream carries MP3 codec + bitrate/sampleRate/channels") {
            val bytes =
                buildMp3File {
                    id3v2 { textFrame("TIT2", "T") }
                    mpegFrames(durationSeconds = 30, bitrate = 64_000, sampleRate = 44_100)
                }
            val result = Mp3Parser().parse(ByteArraySeekableSource(bytes))
            result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            val a = (result as AppResult.Success).data.audioStream
            // mpegFrameHeader encodes channel mode 0b00 (stereo) → 2 channels
            a?.codec shouldBe "mp3"
            a?.bitrate shouldBe 64_000
            a?.sampleRate shouldBe 44_100
            a?.channels shouldBe 2
            a?.codecProfile shouldBe null
            a?.spatial shouldBe null
        }

        test("TIT1 grouping is used as series when no dedicated series tag") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "Book")
                        textFrame("TIT1", "Wheel of Time #3")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.series shouldBe listOf(SeriesEntry("Wheel of Time", "3"))
        }

        test("parse falls back to TPE2 (album-artist) for authors when no TPE1 is present") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "The Sunlit Man")
                        textFrame("TPE2", "Brandon Sanderson")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.authors shouldBe listOf("Brandon Sanderson")
        }

        test("parse prefers TPE1 over TPE2 when both are present") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "The Sunlit Man")
                        textFrame("TPE1", "Brandon Sanderson")
                        textFrame("TPE2", "Macmillan Audio")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.authors shouldBe listOf("Brandon Sanderson")
        }

        test("parse maps TXXX:show to a series name") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "Episode 1")
                        txxxFrame("show", "Welcome to Night Vale")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.series shouldBe listOf(SeriesEntry("Welcome to Night Vale", null))
        }

        test("parse: TXXX:show never overrides an explicit TXXX:series") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "Episode 1")
                        txxxFrame("show", "Marketing Show Name")
                        txxxFrame("series", "Foundation")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.series shouldBe listOf(SeriesEntry("Foundation", null))
        }

        test("parse maps TXXX:episode_id and movement aliases to a series part") {
            val bytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "Episode 2")
                        txxxFrame("series", "Foundation")
                        txxxFrame("episode_id", "2")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val result = parser.parse(byteSource(bytes))
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.series shouldBe listOf(SeriesEntry("Foundation", "2"))

            val movementBytes =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "Episode 3")
                        txxxFrame("series", "Foundation")
                        txxxFrame("movement index", "3")
                    }
                    mpegFrames(durationSeconds = 1)
                }
            val movementResult = parser.parse(byteSource(movementBytes))
            require(movementResult is AppResult.Success<EmbeddedAudioMetadata>)
            movementResult.data.tags.series shouldBe listOf(SeriesEntry("Foundation", "3"))
        }
    })

internal fun byteSource(bytes: ByteArray): SeekableSource =
    object : SeekableSource {
        private var pos: Long = 0
        override val length: Long = bytes.size.toLong()

        override fun position(): Long = pos

        override fun seek(offset: Long) {
            pos = offset
        }

        override fun read(
            into: ByteArray,
            count: Int,
        ): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(count, bytes.size - pos.toInt())
            System.arraycopy(bytes, pos.toInt(), into, 0, n)
            pos += n
            return n
        }

        override fun readFully(count: Int): ByteArray {
            if (pos + count > bytes.size) throw IOException("EOF: requested $count from pos=$pos, length=$length")
            val out = ByteArray(count)
            System.arraycopy(bytes, pos.toInt(), out, 0, count)
            pos += count
            return out
        }

        override fun close() {}
    }
