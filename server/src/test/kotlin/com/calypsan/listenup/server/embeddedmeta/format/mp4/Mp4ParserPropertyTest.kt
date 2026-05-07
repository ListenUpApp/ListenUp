@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.calypsan.listenup.server.embeddedmeta.format.mp4

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.ChapterSource
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.server.embeddedmeta.fixtures.NeroChapter
import com.calypsan.listenup.server.embeddedmeta.fixtures.TextTrackChapter
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp4File
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.ascii
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking

/**
 * Property tests for [Mp4Parser]: build random MP4 fixtures via
 * [buildMp4File] and assert that the parser round-trips the input fields.
 *
 * Generators are intentionally narrow — only inputs that round-trip cleanly
 * through the encoder/decoder pair are covered. ASCII-only text avoids
 * trailing-space ambiguity (the parser strips trailing ASCII spaces from
 * tag values per the Go reference's convention) and keeps the focus on
 * parser-side bugs.
 */
class Mp4ParserPropertyTest : FunSpec({
    val parser = Mp4Parser()
    val asciiText =
        Arb.string(minSize = 1, maxSize = 40, codepoints = Codepoint.ascii()).filter { s ->
            s.isNotBlank() && s.none { it.code == 0 } && !s.endsWith(" ")
        }

    test("title round-trips through ©nam ilst tag") {
        runBlocking {
            checkAll(PropTestConfig(iterations = 30), asciiText) { title ->
                val bytes =
                    buildMp4File {
                        ftyp()
                        moov {
                            mvhd(timescale = 1000, durationInTimescale = 1000)
                            udta { meta { tag("©nam", title) } }
                            audioTrack()
                        }
                    }
                val result = parser.parse(byteSource(bytes))
                require(result is AppResult.Success<EmbeddedAudioMetadata>)
                result.data.tags.title shouldBe title
            }
        }
    }

    test("authors (©ART) round-trip") {
        runBlocking {
            checkAll(PropTestConfig(iterations = 30), asciiText) { artist ->
                val bytes =
                    buildMp4File {
                        ftyp()
                        moov {
                            mvhd(timescale = 1000, durationInTimescale = 1000)
                            udta {
                                meta {
                                    tag("©nam", "Title")
                                    tag("©ART", artist)
                                }
                            }
                            audioTrack()
                        }
                    }
                val result = parser.parse(byteSource(bytes))
                require(result is AppResult.Success<EmbeddedAudioMetadata>)
                result.data.tags.authors shouldBe listOf(artist)
            }
        }
    }

    test("publishedYear (©day) round-trips when in valid range") {
        runBlocking {
            checkAll(PropTestConfig(iterations = 30), Arb.int(1900..2100)) { year ->
                val bytes =
                    buildMp4File {
                        ftyp()
                        moov {
                            mvhd(timescale = 1000, durationInTimescale = 1000)
                            udta {
                                meta {
                                    tag("©nam", "T")
                                    tag("©day", year.toString())
                                }
                            }
                            audioTrack()
                        }
                    }
                val result = parser.parse(byteSource(bytes))
                require(result is AppResult.Success<EmbeddedAudioMetadata>)
                result.data.tags.publishedYear shouldBe year
            }
        }
    }

    test("freeform iTunes Narrator/ASIN atoms map to typed fields") {
        runBlocking {
            val asin =
                Arb.string(minSize = 10, maxSize = 10, codepoints = Codepoint.ascii()).filter { s ->
                    s.all { it.isLetterOrDigit() }
                }
            checkAll(PropTestConfig(iterations = 25), asciiText, asin) { narrator, asinValue ->
                val bytes =
                    buildMp4File {
                        ftyp()
                        moov {
                            mvhd(timescale = 1000, durationInTimescale = 1000)
                            udta {
                                meta {
                                    tag("©nam", "T")
                                    freeform("com.apple.iTunes", "Narrator", narrator)
                                    freeform("com.apple.iTunes", "ASIN", asinValue)
                                }
                            }
                            audioTrack()
                        }
                    }
                val result = parser.parse(byteSource(bytes))
                require(result is AppResult.Success<EmbeddedAudioMetadata>)
                result.data.tags.narrators shouldBe listOf(narrator)
                result.data.tags.asin shouldBe asinValue
            }
        }
    }

    test("variable Nero chpl chapter counts round-trip with computed end times") {
        runBlocking {
            val chapterCount = Arb.int(1..6)
            checkAll(PropTestConfig(iterations = 20), chapterCount) { count ->
                val titles = (0 until count).map { "Chapter ${it + 1}" }
                val durationMs = (count * 30_000L).coerceAtLeast(30_000L)
                val bytes =
                    buildMp4File {
                        ftyp()
                        moov {
                            mvhd(timescale = 1000, durationInTimescale = durationMs)
                            udta {
                                meta { tag("©nam", "Book") }
                                chpl(
                                    chapters =
                                        titles.mapIndexed { i, t ->
                                            NeroChapter(startMs = i * 30_000L, title = t)
                                        },
                                )
                            }
                            audioTrack()
                        }
                    }
                val result = parser.parse(byteSource(bytes))
                require(result is AppResult.Success<EmbeddedAudioMetadata>)
                result.data.chapters.map { it.title } shouldBe titles
                result.data.chaptersSource shouldBe ChapterSource.Mp4Chpl
                // Every chapter except the last ends 1 ms before the next.
                for (i in 0 until count - 1) {
                    result.data.chapters[i].endMs shouldBe (i + 1) * 30_000L - 1
                }
                // Last chapter clamps to durationMs.
                result.data.chapters.last().endMs shouldBe durationMs
            }
        }
    }

    test("Apple text-track chapters round-trip when chpl absent") {
        runBlocking {
            val chapterCount = Arb.int(1..5)
            checkAll(PropTestConfig(iterations = 20), chapterCount) { count ->
                val titles = (0 until count).map { "TT-${it + 1}" }
                val bytes =
                    buildMp4File {
                        ftyp()
                        moov {
                            mvhd(timescale = 1000, durationInTimescale = (count * 20_000L).coerceAtLeast(20_000L))
                            udta { meta { tag("©nam", "Book") } }
                            audioTrack(trackId = 1, chapterTrackRef = 2)
                            chapterTextTrack(
                                trackId = 2,
                                timescale = 1000,
                                chapters =
                                    titles.mapIndexed { i, t ->
                                        TextTrackChapter(startMs = i * 20_000L, title = t)
                                    },
                            )
                        }
                    }
                val result = parser.parse(byteSource(bytes))
                require(result is AppResult.Success<EmbeddedAudioMetadata>)
                result.data.chaptersSource shouldBe ChapterSource.Mp4TextTrack
                result.data.chapters.map { it.title } shouldBe titles
            }
        }
    }

    test("Nero chpl wins precedence when both Nero and Apple text-track present") {
        runBlocking {
            checkAll(PropTestConfig(iterations = 15), Arb.int(1..4)) { neroCount ->
                val neroTitles = (0 until neroCount).map { "Nero-${it + 1}" }
                val bytes =
                    buildMp4File {
                        ftyp()
                        moov {
                            mvhd(timescale = 1000, durationInTimescale = 60_000)
                            udta {
                                meta { tag("©nam", "Book") }
                                chpl(
                                    chapters =
                                        neroTitles.mapIndexed { i, t ->
                                            NeroChapter(startMs = i * 10_000L, title = t)
                                        },
                                )
                            }
                            audioTrack(trackId = 1, chapterTrackRef = 2)
                            chapterTextTrack(
                                trackId = 2,
                                timescale = 1000,
                                chapters =
                                    listOf(
                                        TextTrackChapter(startMs = 0, title = "Apple-Should-Lose"),
                                        TextTrackChapter(startMs = 30_000L, title = "Apple-Should-Lose-2"),
                                    ),
                            )
                        }
                    }
                val result = parser.parse(byteSource(bytes))
                require(result is AppResult.Success<EmbeddedAudioMetadata>)
                result.data.chaptersSource shouldBe ChapterSource.Mp4Chpl
                result.data.chapters.map { it.title } shouldBe neroTitles
            }
        }
    }

    test("cover artwork MIME inferred from leading bytes (JPEG vs PNG)") {
        runBlocking {
            val mimePick = Arb.element("image/jpeg", "image/png")
            val payloadBytes = Arb.list(Arb.int(0..255), 8..32)
            checkAll(PropTestConfig(iterations = 20), mimePick, payloadBytes) { mime, ints ->
                val magic =
                    if (mime == "image/jpeg") {
                        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
                    } else {
                        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
                    }
                val image = magic + ints.map { it.toByte() }.toByteArray()
                val bytes =
                    buildMp4File {
                        ftyp()
                        moov {
                            mvhd(timescale = 1000, durationInTimescale = 1000)
                            udta {
                                meta {
                                    tag("©nam", "T")
                                    cover(mime = mime, bytes = image)
                                }
                            }
                            audioTrack()
                        }
                    }
                val result = parser.parse(byteSource(bytes))
                require(result is AppResult.Success<EmbeddedAudioMetadata>)
                result.data.artwork?.mime shouldBe mime
                result.data.artwork?.bytes?.toList() shouldBe image.toList()
            }
        }
    }

    test("durationMs round-trips across mvhd v0 + v1 (variable timescale + duration)") {
        runBlocking {
            // Stay below v0's 32-bit ceiling so the DSL emits v0 — exercising
            // that path. The v1 path is covered by [Mp4ParserTest].
            val timescale = Arb.element(1, 100, 1000, 44_100, 48_000)
            val durationUnits = Arb.long(1L..100_000_000L)
            checkAll(PropTestConfig(iterations = 30), timescale, durationUnits) { ts, du ->
                val expected = (du * 1000L) / ts.toLong()
                val bytes =
                    buildMp4File {
                        ftyp()
                        moov {
                            mvhd(timescale = ts, durationInTimescale = du)
                            udta { meta { tag("©nam", "T") } }
                            audioTrack()
                        }
                    }
                val result = parser.parse(byteSource(bytes))
                require(result is AppResult.Success<EmbeddedAudioMetadata>)
                result.data.durationMs shouldBe expected
            }
        }
    }

    test("empty udta returns Success with empty fields") {
        runBlocking {
            checkAll(PropTestConfig(iterations = 10), Arb.int(1..3600)) { seconds ->
                val bytes =
                    buildMp4File {
                        ftyp()
                        moov {
                            mvhd(timescale = 1000, durationInTimescale = seconds * 1000L)
                            audioTrack()
                        }
                    }
                val result = parser.parse(byteSource(bytes))
                require(result is AppResult.Success<EmbeddedAudioMetadata>)
                result.data.tags.title shouldBe null
                result.data.tags.authors shouldBe emptyList()
                result.data.chapters shouldBe emptyList()
                result.data.chaptersSource shouldBe ChapterSource.None
                result.data.artwork shouldBe null
                result.data.durationMs shouldBe seconds * 1000L
            }
        }
    }

    test("format is always Mp4 across all generated inputs") {
        runBlocking {
            checkAll(PropTestConfig(iterations = 20), asciiText) { title ->
                val bytes =
                    buildMp4File {
                        ftyp()
                        moov {
                            mvhd(timescale = 1000, durationInTimescale = 1000)
                            udta { meta { tag("©nam", title) } }
                            audioTrack()
                        }
                    }
                val result = parser.parse(byteSource(bytes))
                require(result is AppResult.Success<EmbeddedAudioMetadata>)
                result.data.format shouldBe AudioFormat.Mp4
            }
        }
    }
})
