package com.calypsan.listenup.server.scanner.sidecar

import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlinx.io.files.Path as IoPath

class OpfParserTest :
    FunSpec({

        val parser = OpfParser()

        fun fixture(name: String): IoPath {
            val resource = checkNotNull(OpfParserTest::class.java.classLoader.getResource("sidecar/$name"))
            return IoPath(Path.of(resource.toURI()).toString())
        }

        test("parses well-formed .opf") {
            runTest {
                val md = parser.parse(fixture("sample.opf"))

                md.shouldNotBeNull()
                md.title shouldBe "Words of Radiance"
                checkNotNull(md.description) shouldContain "Stormlight"
                md.publishYear shouldBe 2014
                md.publisher shouldBe "Tor Books"
                md.language shouldBe "en"
                md.contributors shouldContain SidecarContributor("Brandon Sanderson", "author")
                md.contributors shouldContain SidecarContributor("Michael Kramer", "narrator")
            }
        }

        test("multiple dc:creator with opf:role map to correct contributor roles") {
            runTest {
                val md = parser.parse(fixture("multiple-creators.opf"))

                md.shouldNotBeNull()
                md.contributors.filter { it.role == "author" }.map { it.name } shouldBe
                    listOf("Brandon Sanderson", "Peter Ahlstrom")
                md.contributors.filter { it.role == "narrator" }.map { it.name } shouldBe
                    listOf("Michael Kramer", "Kate Reading")
            }
        }

        test("malformed XML returns null") {
            runTest {
                parser.parse(fixture("malformed.opf")).shouldBeNull()
            }
        }

        test("minimal .opf returns SidecarMetadata with only title") {
            runTest {
                val md = parser.parse(fixture("minimal.opf"))

                md.shouldNotBeNull()
                md.title shouldBe "Just A Title"
                md.description.shouldBeNull()
                md.publishYear.shouldBeNull()
                md.publisher.shouldBeNull()
                md.language.shouldBeNull()
                md.contributors shouldBe emptyList()
            }
        }

        test("supportedExtensions == setOf(\"opf\") and supportedFilenames is empty") {
            parser.supportedExtensions shouldBe setOf("opf")
            parser.supportedFilenames shouldBe emptySet()
        }

        test("dc:date with full ISO timestamp still yields the year") {
            runTest {
                val md = parser.parse(fixture("iso-timestamp.opf"))

                md.shouldNotBeNull()
                md.publishYear shouldBe 2014
            }
        }

        test("opf:role maps known codes; unknown and unsupported roles are dropped") {
            runTest {
                val md = parser.parse(fixture("creator-roles.opf"))

                md.shouldNotBeNull()
                md.contributors shouldContain SidecarContributor("Brandon Sanderson", "author")
                md.contributors shouldContain SidecarContributor("Michael Kramer", "narrator")
                md.contributors shouldContain SidecarContributor("Peter Ahlstrom", "author")
                md.contributors.map { it.name } shouldNotContain "Translator Person"
                md.contributors.map { it.name } shouldNotContain "Editor Person"
            }
        }

        test("dc:date refuses to guess a year from free-text prose") {
            runTest {
                parser.parse(inlineOpf("2014-03-01"))?.publishYear shouldBe 2014
                parser.parse(inlineOpf("2014"))?.publishYear shouldBe 2014
                parser.parse(inlineOpf("Reprinted 1999, first published 2014"))?.publishYear.shouldBeNull()
            }
        }

        test("reads dc:subject elements into SidecarMetadata.genres") {
            runTest {
                val md = parser.parse(fixture("sample.opf"))

                md.shouldNotBeNull()
                md.genres shouldBe listOf("Fantasy", "Epic")
            }
        }

        test("absent dc:subject yields empty genres") {
            runTest {
                parser.parse(fixture("minimal.opf"))?.genres shouldBe emptyList()
            }
        }

        test("reads dc:subtitle into SidecarMetadata.subtitle") {
            runTest {
                val opfPath = inlineOpfWithSubtitle(title = "Mistborn", subtitle = "The Final Empire")
                parser.parse(opfPath)?.subtitle shouldBe "The Final Empire"
            }
        }

        test("absent dc:subtitle yields null subtitle") {
            runTest {
                val opfPath = inlineOpfNoSubtitle(title = "Mistborn")
                parser.parse(opfPath)?.subtitle.shouldBeNull()
            }
        }

        test("reads Calibre series + series_index into SidecarMetadata.series") {
            runTest {
                val opfPath =
                    inlineOpfWithCalibreSeries(
                        title = "Words of Radiance",
                        seriesName = "The Stormlight Archive",
                        seriesIndex = "2",
                    )
                parser.parse(opfPath)?.series shouldBe
                    listOf(SeriesEntry(name = "The Stormlight Archive", sequence = "2"))
            }
        }

        test("absent Calibre series yields empty series") {
            runTest {
                val opfPath = inlineOpfNoSubtitle(title = "Mistborn")
                parser.parse(opfPath)?.series shouldBe emptyList()
            }
        }

        test("Calibre series present but series_index absent yields null sequence") {
            runTest {
                val opfPath = inlineOpfWithSeriesOnly(seriesName = "The Wheel of Time")
                parser.parse(opfPath)?.series shouldBe listOf(SeriesEntry(name = "The Wheel of Time", sequence = null))
            }
        }
    })

private fun inlineOpf(date: String): IoPath {
    val temp = kotlin.io.path.createTempFile(suffix = ".opf")
    temp.toFile().writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0">
            <metadata>
                <dc:title>Date Test</dc:title>
                <dc:date>$date</dc:date>
            </metadata>
        </package>
        """.trimIndent(),
    )
    temp.toFile().deleteOnExit()
    return IoPath(temp.toString())
}

private fun inlineOpfWithSubtitle(
    title: String,
    subtitle: String,
): IoPath {
    val temp = kotlin.io.path.createTempFile(suffix = ".opf")
    temp.toFile().writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0">
            <metadata>
                <dc:title>$title</dc:title>
                <dc:subtitle>$subtitle</dc:subtitle>
            </metadata>
        </package>
        """.trimIndent(),
    )
    temp.toFile().deleteOnExit()
    return IoPath(temp.toString())
}

private fun inlineOpfNoSubtitle(title: String): IoPath {
    val temp = kotlin.io.path.createTempFile(suffix = ".opf")
    temp.toFile().writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0">
            <metadata>
                <dc:title>$title</dc:title>
            </metadata>
        </package>
        """.trimIndent(),
    )
    temp.toFile().deleteOnExit()
    return IoPath(temp.toString())
}

private fun inlineOpfWithCalibreSeries(
    title: String,
    seriesName: String,
    seriesIndex: String,
): IoPath {
    val temp = kotlin.io.path.createTempFile(suffix = ".opf")
    temp.toFile().writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0">
            <metadata>
                <dc:title>$title</dc:title>
                <meta name="calibre:series" content="$seriesName"/>
                <meta name="calibre:series_index" content="$seriesIndex"/>
            </metadata>
        </package>
        """.trimIndent(),
    )
    temp.toFile().deleteOnExit()
    return IoPath(temp.toString())
}

private fun inlineOpfWithSeriesOnly(seriesName: String): IoPath {
    val temp = kotlin.io.path.createTempFile(suffix = ".opf")
    temp.toFile().writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0">
            <metadata>
                <dc:title>Series Only Test</dc:title>
                <meta name="calibre:series" content="$seriesName"/>
            </metadata>
        </package>
        """.trimIndent(),
    )
    temp.toFile().deleteOnExit()
    return IoPath(temp.toString())
}
