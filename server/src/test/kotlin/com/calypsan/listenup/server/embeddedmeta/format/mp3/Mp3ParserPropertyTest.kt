@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.calypsan.listenup.server.embeddedmeta.format.mp3

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.ChapterSource
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.ascii
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking

/**
 * Property tests for [Mp3Parser]: build random MP3 fixtures via [buildMp3File]
 * and assert that the parser round-trips the input fields back. Generators are
 * intentionally narrow — only inputs that round-trip cleanly through the
 * encoder/decoder pair are covered here. ASCII-only text avoids encoder
 * ambiguities (BOM placement, surrogate pairs, single-byte vs multi-byte
 * null terminators) that would obscure parser-side bugs.
 */
class Mp3ParserPropertyTest :
    FunSpec({
        val parser = Mp3Parser()
        val asciiText =
            Arb.string(minSize = 1, maxSize = 40, codepoints = Codepoint.ascii()).filter { s ->
                s.isNotBlank() && s.none { it.code == 0 }
            }
        val versionArb = Arb.element(3, 4)

        test("title round-trips through ID3v2.3 + 2.4 text frames") {
            runBlocking {
                checkAll(PropTestConfig(iterations = 30), versionArb, asciiText) { version, title ->
                    val bytes =
                        buildMp3File {
                            id3v2(version = version) { textFrame("TIT2", title) }
                            mpegFrames(durationSeconds = 1)
                        }
                    val result = parser.parse(byteSource(bytes))
                    require(result is AppResult.Success<EmbeddedAudioMetadata>)
                    result.data.tags.title shouldBe title
                }
            }
        }

        test("authors (TPE1) round-trip") {
            runBlocking {
                checkAll(PropTestConfig(iterations = 30), versionArb, asciiText) { version, artist ->
                    val bytes =
                        buildMp3File {
                            id3v2(version = version) {
                                textFrame("TIT2", "Title")
                                textFrame("TPE1", artist)
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val result = parser.parse(byteSource(bytes))
                    require(result is AppResult.Success<EmbeddedAudioMetadata>)
                    result.data.tags.authors shouldBe listOf(artist)
                }
            }
        }

        test("album lands in tags.custom['album']") {
            runBlocking {
                checkAll(PropTestConfig(iterations = 30), asciiText) { album ->
                    val bytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Title")
                                textFrame("TALB", album)
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val result = parser.parse(byteSource(bytes))
                    require(result is AppResult.Success<EmbeddedAudioMetadata>)
                    result.data.tags.custom["album"] shouldBe album
                }
            }
        }

        test("genre (TCON) appears in tags.genres") {
            runBlocking {
                checkAll(PropTestConfig(iterations = 30), asciiText) { genre ->
                    val bytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "T")
                                textFrame("TCON", genre)
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val result = parser.parse(byteSource(bytes))
                    require(result is AppResult.Success<EmbeddedAudioMetadata>)
                    result.data.tags.genres shouldBe listOf(genre)
                }
            }
        }

        test("publishedYear (TYER) round-trips when in valid range") {
            runBlocking {
                checkAll(PropTestConfig(iterations = 30), Arb.int(1900..2100)) { year ->
                    val bytes =
                        buildMp3File {
                            id3v2(version = 3) {
                                textFrame("TIT2", "T")
                                textFrame("TYER", year.toString())
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val result = parser.parse(byteSource(bytes))
                    require(result is AppResult.Success<EmbeddedAudioMetadata>)
                    result.data.tags.publishedYear shouldBe year
                }
            }
        }

        test("trackNumber (TRCK) round-trips") {
            runBlocking {
                checkAll(PropTestConfig(iterations = 30), Arb.int(1..999)) { track ->
                    val bytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "T")
                                textFrame("TRCK", track.toString())
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val result = parser.parse(byteSource(bytes))
                    require(result is AppResult.Success<EmbeddedAudioMetadata>)
                    result.data.tags.trackNumber shouldBe track
                }
            }
        }

        test("TXXX user-defined frames preserve description→value mapping (unmapped keys land in custom)") {
            runBlocking {
                val customKey =
                    Arb
                        .string(minSize = 4, maxSize = 12, codepoints = Codepoint.ascii())
                        .filter { s -> s.none { it.code == 0 } && s.lowercase() !in RESERVED_TXXX_KEYS }
                checkAll(PropTestConfig(iterations = 30), customKey, asciiText) { key, value ->
                    val bytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Title")
                                txxxFrame(key, value)
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val result = parser.parse(byteSource(bytes))
                    require(result is AppResult.Success<EmbeddedAudioMetadata>)
                    result.data.tags.custom[key] shouldBe value
                }
            }
        }

        test("variable chapter counts round-trip into chapters list") {
            runBlocking {
                val chapterCount = Arb.int(1..6)
                checkAll(PropTestConfig(iterations = 25), chapterCount) { count ->
                    val titles = (0 until count).map { "Chapter ${it + 1}" }
                    val bytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Book")
                                titles.forEachIndexed { i, t ->
                                    chapFrame("ch${i + 1}", startMs = i * 30_000, endMs = (i + 1) * 30_000, title = t)
                                }
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val result = parser.parse(byteSource(bytes))
                    require(result is AppResult.Success<EmbeddedAudioMetadata>)
                    result.data.chapters.map { it.title } shouldBe titles
                    result.data.chaptersSource shouldBe ChapterSource.Id3v2Chap
                }
            }
        }

        test("optional artwork round-trips (presence and bytes)") {
            runBlocking {
                checkAll(PropTestConfig(iterations = 25), Arb.boolean(), Arb.list(Arb.int(0..255), 16..32)) { include, ints ->
                    val image = ints.map { it.toByte() }.toByteArray()
                    val bytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Book")
                                if (include) {
                                    apicFrame(mime = "image/png", pictureType = 3, description = "Cover", imageBytes = image)
                                }
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val result = parser.parse(byteSource(bytes))
                    require(result is AppResult.Success<EmbeddedAudioMetadata>)
                    if (include) {
                        result.data.artwork
                            ?.bytes
                            ?.toList() shouldBe image.toList()
                    } else {
                        result.data.artwork shouldBe null
                    }
                }
            }
        }

        test("format is always Mp3 across all generated inputs") {
            runBlocking {
                checkAll(PropTestConfig(iterations = 20), versionArb, asciiText) { version, title ->
                    val bytes =
                        buildMp3File {
                            id3v2(version = version) { textFrame("TIT2", title) }
                            mpegFrames(durationSeconds = 1)
                        }
                    val result = parser.parse(byteSource(bytes))
                    require(result is AppResult.Success<EmbeddedAudioMetadata>)
                    result.data.format shouldBe AudioFormat.Mp3
                }
            }
        }

        test("ID3v1 fallback round-trips title and artist when no ID3v2") {
            runBlocking {
                // ID3v1 truncates to 30 bytes per field; restrict generator length accordingly.
                // ID3v1 also strips trailing nulls and ASCII spaces (0x20) as part of the field's
                // fixed-width padding convention, so the generator excludes both.
                val shortAscii =
                    Arb
                        .string(minSize = 1, maxSize = 20, codepoints = Codepoint.ascii())
                        .filter { s -> s.isNotBlank() && s.none { it.code == 0 } && !s.endsWith(" ") }
                checkAll(PropTestConfig(iterations = 25), shortAscii, shortAscii) { title, artist ->
                    val bytes =
                        buildMp3File {
                            mpegFrames(durationSeconds = 1)
                            id3v1(title = title, artist = artist)
                        }
                    val result = parser.parse(byteSource(bytes))
                    require(result is AppResult.Success<EmbeddedAudioMetadata>)
                    result.data.tags.title shouldBe title
                    result.data.tags.authors shouldBe listOf(artist)
                }
            }
        }
    })

private val RESERVED_TXXX_KEYS: Set<String> =
    setOf(
        "narrator",
        "series",
        "series part",
        "seriespart",
        "part",
        "series-part",
        "series position",
        "publisher",
        "isbn",
        "asin",
        "audible_asin",
        "language",
        "lang",
        "description",
    )
