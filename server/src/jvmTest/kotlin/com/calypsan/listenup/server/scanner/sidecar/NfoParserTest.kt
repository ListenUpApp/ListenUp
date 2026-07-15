package com.calypsan.listenup.server.scanner.sidecar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlinx.io.files.Path as IoPath

class NfoParserTest :
    FunSpec({

        val parser = NfoParser()

        fun fixture(name: String): IoPath {
            val resource = checkNotNull(NfoParserTest::class.java.classLoader.getResource("sidecar/$name"))
            return IoPath(Path.of(resource.toURI()).toString())
        }

        test("parses well-formed .nfo") {
            runTest {
                val md = parser.parse(fixture("sample.nfo"))

                md.shouldNotBeNull()
                md.title shouldBe "Way of Kings"
                checkNotNull(md.description) shouldContain "epic fantasy"
                md.publishYear shouldBe 2010
                md.publisher shouldBe "Tor Books"
                md.language shouldBe "en"
                md.contributors.count { it.role == "author" } shouldBeGreaterThanOrEqual 1
                md.contributors.count { it.role == "narrator" } shouldBeGreaterThanOrEqual 2
            }
        }

        test("reads <subtitle> into SidecarMetadata.subtitle") {
            runTest {
                val md = parser.parse(fixture("sample.nfo"))

                md.shouldNotBeNull()
                md.subtitle shouldBe "The Stormlight Archive, Book One"
            }
        }

        test("reads <genre> elements into SidecarMetadata.genres") {
            runTest {
                val md = parser.parse(fixture("sample.nfo"))

                md.shouldNotBeNull()
                md.genres shouldBe listOf("Fantasy", "Epic")
            }
        }

        test("malformed XML returns null") {
            runTest {
                parser.parse(fixture("malformed.nfo")).shouldBeNull()
            }
        }

        test("minimal .nfo with only <title> returns SidecarMetadata with other fields null") {
            runTest {
                val md = parser.parse(fixture("minimal.nfo"))

                md.shouldNotBeNull()
                md.title shouldBe "Just A Title"
                md.description.shouldBeNull()
                md.publishYear.shouldBeNull()
                md.publisher.shouldBeNull()
                md.contributors shouldBe emptyList()
            }
        }

        test("supportedExtensions == setOf(\"nfo\") and supportedFilenames is empty") {
            parser.supportedExtensions shouldBe setOf("nfo")
            parser.supportedFilenames shouldBe emptySet()
        }

        test("actor names are extracted as narrator contributors") {
            runTest {
                val md = parser.parse(fixture("sample.nfo"))

                md.shouldNotBeNull()
                md.contributors shouldContain SidecarContributor("Michael Kramer", "narrator")
                md.contributors shouldContain SidecarContributor("Kate Reading", "narrator")
            }
        }
    })
