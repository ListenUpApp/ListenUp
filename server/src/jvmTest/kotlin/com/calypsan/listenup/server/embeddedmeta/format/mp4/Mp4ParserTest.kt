package com.calypsan.listenup.server.embeddedmeta.format.mp4

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.ChapterSource
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.domain.embeddedmeta.SeriesEntry
import com.calypsan.listenup.server.io.SeekableSource
import com.calypsan.listenup.domain.embeddedmeta.AudioStreamInfo
import com.calypsan.listenup.server.embeddedmeta.fixtures.NeroChapter
import com.calypsan.listenup.server.embeddedmeta.fixtures.TextTrackChapter
import com.calypsan.listenup.server.embeddedmeta.fixtures.ac4Entry
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp4File
import com.calypsan.listenup.server.embeddedmeta.fixtures.mp4aEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * End-to-end fixture-driven tests for [Mp4Parser]. The DSL builds a synthetic
 * MP4 byte stream; the parser walks it and surfaces tags, chapters, artwork
 * and duration. Property tests live in [Mp4ParserPropertyTest].
 */
class Mp4ParserTest :
    FunSpec({
        val parser = Mp4Parser()

        test("supports = setOf(AudioFormat.Mp4)") {
            parser.supports shouldBe setOf(AudioFormat.Mp4)
        }

        test("parse extracts title + artist from ©nam / ©ART ilst tags") {
            val bytes =
                buildMp4File {
                    ftyp(brand = "M4B ")
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 60_000)
                        udta {
                            meta {
                                tag("©nam", "The Way of Kings")
                                tag("©ART", "Brandon Sanderson")
                                tag("©alb", "The Stormlight Archive")
                            }
                        }
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.format shouldBe AudioFormat.Mp4
            result.data.tags.title shouldBe "The Way of Kings"
            result.data.tags.authors shouldBe listOf("Brandon Sanderson")
            result.data.tags.custom["album"] shouldBe "The Stormlight Archive"
        }

        test("parse computes durationMs from mvhd timescale + duration (v0)") {
            val bytes =
                buildMp4File {
                    ftyp()
                    moov {
                        // 90_000 timescale units / 1000 Hz = 90 seconds = 90_000 ms
                        mvhd(timescale = 1000, durationInTimescale = 90_000)
                        udta { meta { tag("©nam", "Book") } }
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.durationMs shouldBe 90_000L
        }

        test("parse computes durationMs from mvhd v1 (64-bit duration)") {
            val bytes =
                buildMp4File {
                    ftyp()
                    moov {
                        // Force v1 by exceeding Int.MAX_VALUE — 1 hour at 1e9 timescale
                        mvhd(timescale = 1_000_000_000, durationInTimescale = 3_600L * 1_000_000_000L)
                        udta { meta { tag("©nam", "Long Book") } }
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.durationMs shouldBe 3_600_000L // 1 hour in ms
        }

        test("parse extracts Nero chpl chapters with computed end times") {
            val bytes =
                buildMp4File {
                    ftyp()
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 90_000)
                        udta {
                            meta { tag("©nam", "Book") }
                            chpl(
                                chapters =
                                    listOf(
                                        NeroChapter(startMs = 0, title = "Prologue"),
                                        NeroChapter(startMs = 30_000, title = "Chapter 1"),
                                        NeroChapter(startMs = 60_000, title = "Chapter 2"),
                                    ),
                            )
                        }
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.chaptersSource shouldBe ChapterSource.Mp4Chpl
            result.data.chapters shouldHaveSize 3
            result.data.chapters[0].title shouldBe "Prologue"
            result.data.chapters[0].startMs shouldBe 0L
            result.data.chapters[0].endMs shouldBe 29_999L
            result.data.chapters[1].startMs shouldBe 30_000L
            result.data.chapters[1].endMs shouldBe 59_999L
            // Last chapter clamps to durationMs.
            result.data.chapters[2].startMs shouldBe 60_000L
            result.data.chapters[2].endMs shouldBe 90_000L
        }

        test("parse extracts cover artwork (JPEG, sniffed MIME)") {
            val fakeJpeg =
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 'J'.code.toByte(), 'F'.code.toByte())
            val bytes =
                buildMp4File {
                    ftyp()
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 1000)
                        udta {
                            meta {
                                tag("©nam", "Book")
                                cover(mime = "image/jpeg", bytes = fakeJpeg)
                            }
                        }
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.artwork?.mime shouldBe "image/jpeg"
            result.data.artwork
                ?.bytes
                ?.toList() shouldBe fakeJpeg.toList()
        }

        test("parse extracts cover artwork (PNG, sniffed MIME)") {
            val fakePng =
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val bytes =
                buildMp4File {
                    ftyp()
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 1000)
                        udta {
                            meta {
                                tag("©nam", "Book")
                                cover(mime = "image/png", bytes = fakePng)
                            }
                        }
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.artwork?.mime shouldBe "image/png"
            result.data.artwork
                ?.bytes
                ?.toList() shouldBe fakePng.toList()
        }

        test("parse maps com.apple.iTunes/Narrator (---- atom) to tags.narrators") {
            val bytes =
                buildMp4File {
                    ftyp()
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 1000)
                        udta {
                            meta {
                                tag("©nam", "Book")
                                freeform(mean = "com.apple.iTunes", name = "Narrator", value = "Kate Reading")
                                freeform(mean = "com.apple.iTunes", name = "ASIN", value = "B00ABCDEF1")
                            }
                        }
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.narrators shouldBe listOf("Kate Reading")
            result.data.tags.asin shouldBe "B00ABCDEF1"
        }

        test("parse falls back to Apple text-track chapters when chpl absent") {
            val bytes =
                buildMp4File {
                    ftyp()
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 90_000)
                        udta { meta { tag("©nam", "Book") } }
                        audioTrack(trackId = 1, chapterTrackRef = 2)
                        chapterTextTrack(
                            trackId = 2,
                            timescale = 1000,
                            chapters =
                                listOf(
                                    TextTrackChapter(startMs = 0, title = "Prologue"),
                                    TextTrackChapter(startMs = 30_000, title = "Chapter 1"),
                                ),
                        )
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.chaptersSource shouldBe ChapterSource.Mp4TextTrack
            result.data.chapters shouldHaveSize 2
            result.data.chapters[0].title shouldBe "Prologue"
            result.data.chapters[0].startMs shouldBe 0L
            result.data.chapters[1].title shouldBe "Chapter 1"
            result.data.chapters[1].startMs shouldBe 30_000L
        }

        test("parse prefers Nero chpl when both Nero and Apple text-track are present") {
            val bytes =
                buildMp4File {
                    ftyp()
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 90_000)
                        udta {
                            meta { tag("©nam", "Book") }
                            chpl(
                                chapters =
                                    listOf(
                                        NeroChapter(startMs = 0, title = "Nero-A"),
                                        NeroChapter(startMs = 45_000, title = "Nero-B"),
                                    ),
                            )
                        }
                        audioTrack(trackId = 1, chapterTrackRef = 2)
                        chapterTextTrack(
                            trackId = 2,
                            timescale = 1000,
                            chapters =
                                listOf(
                                    TextTrackChapter(startMs = 0, title = "Apple-A"),
                                    TextTrackChapter(startMs = 30_000, title = "Apple-B"),
                                ),
                        )
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.chaptersSource shouldBe ChapterSource.Mp4Chpl
            result.data.chapters.map { it.title } shouldBe listOf("Nero-A", "Nero-B")
        }

        test("parse returns Success with empty chapters when neither source present") {
            val bytes =
                buildMp4File {
                    ftyp()
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 1000)
                        udta { meta { tag("©nam", "Book") } }
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.chapters.size shouldBe 0
            result.data.chaptersSource shouldBe ChapterSource.None
        }

        test("parse returns Success with empty fields when udta is absent") {
            val bytes =
                buildMp4File {
                    ftyp()
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 1000)
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.title shouldBe null
            result.data.tags.authors shouldBe emptyList()
            result.data.chapters shouldBe emptyList()
            result.data.artwork shouldBe null
        }

        test("parse maps ldes long-description atom to description") {
            val bytes =
                buildMp4File {
                    ftyp(brand = "M4B ")
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 1000)
                        udta {
                            meta {
                                tag("©nam", "Book")
                                tag("ldes", "The long description.")
                            }
                        }
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.description shouldBe "The long description."
        }

        test("parse maps a synopsis freeform atom to tags.description") {
            val bytes =
                buildMp4File {
                    ftyp(brand = "M4B ")
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 1000)
                        udta {
                            meta {
                                tag("©nam", "Book")
                                freeform(mean = "com.apple.iTunes", name = "synopsis", value = "The blurb.")
                            }
                        }
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.description shouldBe "The blurb."
        }

        test("sonm and soar atoms populate sort fields") {
            val bytes =
                buildMp4File {
                    ftyp(brand = "M4B ")
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 1000)
                        udta {
                            meta {
                                tag("©nam", "The Way of Kings")
                                tag("sonm", "Way of Kings, The")
                                tag("soar", "Sanderson, Brandon")
                            }
                        }
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
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
            val result = runBlocking { parser.parse(source) }
            require(result is AppResult.Failure)
            (result.error is com.calypsan.listenup.api.error.AudioMetadataError.IoError) shouldBe true
        }

        test("semicolon ©mvn and ©mvi tags zip into multiple series") {
            val bytes =
                buildMp4File {
                    ftyp()
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 1000)
                        udta {
                            meta {
                                tag("©nam", "Words of Radiance")
                                tag("©mvn", "Cosmere;Stormlight")
                                tag("©mvi", "3;4")
                            }
                        }
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.series shouldBe listOf(SeriesEntry("Cosmere", "3"), SeriesEntry("Stormlight", "4"))
        }

        test("©grp grouping is used as series when no dedicated series tag") {
            val bytes =
                buildMp4File {
                    ftyp()
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 1000)
                        udta {
                            meta {
                                tag("©nam", "Book")
                                tag("©grp", "Wheel of Time #3")
                            }
                        }
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.series shouldBe listOf(SeriesEntry("Wheel of Time", "3"))
        }

        test("a dedicated ©mvn series tag wins over ©grp grouping") {
            val bytes =
                buildMp4File {
                    ftyp()
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 1000)
                        udta {
                            meta {
                                tag("©nam", "Book")
                                tag("©mvn", "Real Series")
                                tag("©mvi", "2")
                                tag("©grp", "Grouping Series #9")
                            }
                        }
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.series shouldBe listOf(SeriesEntry("Real Series", "2"))
        }

        test("parse populates audioStream for an ac-4 (Atmos) track") {
            val bytes =
                buildMp4File {
                    ftyp()
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 1000)
                        audioTrack(sampleEntry = ac4Entry(channels = 2, sampleRate = 48000))
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.audioStream shouldBe
                AudioStreamInfo(
                    codec = "ac4",
                    codecProfile = null,
                    spatial = "atmos",
                    bitrate = null,
                    sampleRate = 48000,
                    channels = 2,
                )
        }

        test("parse populates audioStream with xhe-aac profile for mp4a AOT-42") {
            val bytes =
                buildMp4File {
                    ftyp()
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 1000)
                        audioTrack(
                            sampleEntry =
                                mp4aEntry(
                                    channels = 2,
                                    sampleRate = 44100,
                                    ascAot = 42, // xHE-AAC
                                    ascFreqIdx = 4, // 44100 Hz
                                    ascChan = 2,
                                ),
                        )
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.audioStream?.codecProfile shouldBe "xhe"
        }

        test("parse maps the ©st3 track-subtitle atom to subtitle") {
            val bytes =
                buildMp4File {
                    ftyp(brand = "M4B ")
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 1000)
                        udta {
                            meta {
                                tag("©nam", "The Way of Kings")
                                tag("©st3", "Book One of the Stormlight Archive")
                            }
                        }
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.subtitle shouldBe "Book One of the Stormlight Archive"
        }

        test("parse maps the tvsh show atom to a series name") {
            val bytes =
                buildMp4File {
                    ftyp(brand = "M4B ")
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 1000)
                        udta {
                            meta {
                                tag("©nam", "Episode 1")
                                tag("tvsh", "Welcome to Night Vale")
                            }
                        }
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.series shouldBe listOf(SeriesEntry("Welcome to Night Vale", null))
        }

        test("parse maps a freeform show name and episode_id to a series with part") {
            val bytes =
                buildMp4File {
                    ftyp(brand = "M4B ")
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 1000)
                        udta {
                            meta {
                                tag("©nam", "Episode 2")
                                freeform(mean = "com.apple.iTunes", name = "show", value = "The Sandman")
                                freeform(mean = "com.apple.iTunes", name = "episode_id", value = "2")
                            }
                        }
                        audioTrack()
                    }
                }
            val result = runBlocking { parser.parse(byteSource(bytes)) }
            require(result is AppResult.Success<EmbeddedAudioMetadata>)
            result.data.tags.series shouldBe listOf(SeriesEntry("The Sandman", "2"))
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
