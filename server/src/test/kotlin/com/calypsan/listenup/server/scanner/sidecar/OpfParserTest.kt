package com.calypsan.listenup.server.scanner.sidecar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import java.nio.file.Path

class OpfParserTest :
    FunSpec({

        val parser = OpfParser()

        fun fixture(name: String): Path = Path.of(checkNotNull(OpfParserTest::class.java.classLoader.getResource("sidecar/$name")).toURI())

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
    })

private fun inlineOpf(date: String): Path {
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
    return temp
}
