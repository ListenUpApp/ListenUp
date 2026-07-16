package com.calypsan.listenup.server.scanner.metadata

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlinx.io.files.Path as IoPath

class AbsMetadataReaderTest :
    FunSpec({

        val reader = AbsMetadataReader(contractJson)

        test("reads a flat metadata.json with the canonical ABS shape") {
            runTest {
                val tmp = Files.createTempDirectory("listenup-md-")
                try {
                    val file = tmp / "metadata.json"
                    file.writeText(
                        """
                        {
                          "title": "The Way of Kings",
                          "subtitle": "Book One",
                          "authors": ["Brandon Sanderson"],
                          "narrators": ["Michael Kramer", "Kate Reading"],
                          "series": ["Stormlight Archive #1"],
                          "publishedYear": 2010,
                          "asin": "B0015T963C"
                        }
                        """.trimIndent(),
                    )

                    val md = reader.read(IoPath(file.toString()))

                    md.shouldNotBeNull()
                    md.title shouldBe "The Way of Kings"
                    md.subtitle shouldBe "Book One"
                    md.authors shouldBe listOf("Brandon Sanderson")
                    md.narrators shouldBe listOf("Michael Kramer", "Kate Reading")
                    md.series shouldBe listOf("Stormlight Archive #1")
                    md.publishedYear shouldBe 2010
                    md.asin shouldBe "B0015T963C"
                } finally {
                    tmp.toFile().deleteRecursively()
                }
            }
        }

        test("auto-flattens the legacy nested 'metadata' wrapper") {
            runTest {
                val tmp = Files.createTempDirectory("listenup-md-")
                try {
                    val file = tmp / "metadata.json"
                    file.writeText("""{"metadata":{"title":"Legacy Title","authors":["Author"]}}""")

                    val md = reader.read(IoPath(file.toString()))

                    md.shouldNotBeNull()
                    md.title shouldBe "Legacy Title"
                    md.authors shouldBe listOf("Author")
                } finally {
                    tmp.toFile().deleteRecursively()
                }
            }
        }

        test("returns null on malformed JSON without throwing") {
            runTest {
                val tmp = Files.createTempDirectory("listenup-md-")
                try {
                    val file = tmp / "metadata.json"
                    file.writeText("{this is not valid json")

                    reader.read(IoPath(file.toString())).shouldBeNull()
                } finally {
                    tmp.toFile().deleteRecursively()
                }
            }
        }

        test("returns null when the file doesn't exist") {
            runTest {
                val tmp = Files.createTempDirectory("listenup-md-")
                try {
                    reader.read(IoPath((tmp / "nope.json").toString())).shouldBeNull()
                } finally {
                    tmp.toFile().deleteRecursively()
                }
            }
        }

        test("returns null for a directory passed as a path") {
            runTest {
                val tmp = Files.createTempDirectory("listenup-md-")
                try {
                    (tmp / "sub").createDirectories()
                    reader.read(IoPath((tmp / "sub").toString())).shouldBeNull()
                } finally {
                    tmp.toFile().deleteRecursively()
                }
            }
        }

        test("parseSeriesEntries handles plain name (no #)") {
            reader.parseSeriesEntries(listOf("Standalone")) shouldBe listOf(SeriesEntry("Standalone"))
        }

        test("parseSeriesEntries parses 'Name #seq' canonical form") {
            reader.parseSeriesEntries(listOf("Stormlight Archive #1")) shouldBe
                listOf(SeriesEntry("Stormlight Archive", "1"))
        }

        test("parseSeriesEntries preserves string sequences (1.5, 0a)") {
            reader.parseSeriesEntries(listOf("Wheel of Time #5", "Mistborn #1.5", "Cosmere #0a")) shouldContainInOrder
                listOf(
                    SeriesEntry("Wheel of Time", "5"),
                    SeriesEntry("Mistborn", "1.5"),
                    SeriesEntry("Cosmere", "0a"),
                )
        }

        test("parseSeriesEntries strips empty entries") {
            reader.parseSeriesEntries(listOf("", "  ", "Real Series #1")) shouldBe
                listOf(SeriesEntry("Real Series", "1"))
        }

        test("parseSeriesEntries with empty sequence (#) keeps name only") {
            reader.parseSeriesEntries(listOf("Name #")) shouldBe listOf(SeriesEntry("Name"))
        }

        test("AbsMetadata accepts the flat schema with all optional fields absent") {
            val flat = """{"title":"Hello","authors":["Alice"]}"""
            val parsed = contractJson.decodeFromString<AbsMetadata>(flat)
            parsed.title shouldBe "Hello"
            parsed.authors shouldBe listOf("Alice")
            parsed.series shouldBe emptyList()
        }

        test("AbsMetadata round-trips with chapters") {
            val md =
                AbsMetadata(
                    title = "Hello",
                    authors = listOf("Alice"),
                    series = listOf("Series #1"),
                    chapters = listOf(AbsChapter(0, 0.0, 60.0, "Chapter 1")),
                )
            contractJson.decodeFromString<AbsMetadata>(contractJson.encodeToString(md)) shouldBe md
        }
    })
