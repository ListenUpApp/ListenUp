package com.calypsan.listenup.server.scanner.sidecar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlinx.io.files.Path as IoPath

class PlainTextParsersTest :
    FunSpec({

        fun fixture(name: String): IoPath {
            val url = checkNotNull(PlainTextParsersTest::class.java.classLoader.getResource("sidecar/$name"))
            return IoPath(Path.of(url.toURI()).toString())
        }

        context("ReaderTxtParser") {
            val parser = ReaderTxtParser()

            test("maps each line to a narrator contributor") {
                runTest {
                    val md = parser.parse(fixture("reader.txt"))

                    md.shouldNotBeNull()
                    md.contributors shouldContainExactly
                        listOf(
                            SidecarContributor("Michael Kramer", "narrator"),
                            SidecarContributor("Kate Reading", "narrator"),
                        )
                }
            }

            test("tolerates BOM + trailing whitespace + blank lines") {
                runTest {
                    val tmp = Files.createTempFile("reader-bom", ".txt")
                    try {
                        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                        val body =
                            """
                            Michael Kramer
                              Kate Reading

                            """.trimIndent()
                        Files.write(tmp, bom + body.toByteArray())
                        val md = parser.parse(IoPath(tmp.toString()))

                        md.shouldNotBeNull()
                        md.contributors shouldHaveSize 2
                        md.contributors shouldContainExactly
                            listOf(
                                SidecarContributor("Michael Kramer", "narrator"),
                                SidecarContributor("Kate Reading", "narrator"),
                            )
                    } finally {
                        tmp.deleteIfExists()
                    }
                }
            }

            test("returns null on empty file") {
                runTest {
                    parser.parse(fixture("reader-empty.txt")).shouldBeNull()
                }
            }

            test("supportedFilenames == setOf(\"reader.txt\") and supportedExtensions is empty") {
                parser.supportedFilenames shouldBe setOf("reader.txt")
                parser.supportedExtensions shouldBe emptySet()
            }
        }

        context("DescTxtParser") {
            val parser = DescTxtParser()

            test("captures single-line description") {
                runTest {
                    val md = parser.parse(fixture("desc.txt"))

                    md.shouldNotBeNull()
                    md.description shouldBe
                        "A sweeping epic fantasy about the return of magic to a broken world."
                }
            }

            test("captures multi-line description") {
                runTest {
                    val md = parser.parse(fixture("desc-multiline.txt"))

                    md.shouldNotBeNull()
                    checkNotNull(md.description) shouldContain "\n"
                }
            }

            test("returns null on empty file") {
                runTest {
                    val tmp = Files.createTempFile("desc-empty", ".txt")
                    try {
                        tmp.writeText("")
                        parser.parse(IoPath(tmp.toString())).shouldBeNull()
                    } finally {
                        tmp.deleteIfExists()
                    }
                }
            }

            test("supportedFilenames == setOf(\"desc.txt\") and supportedExtensions is empty") {
                parser.supportedFilenames shouldBe setOf("desc.txt")
                parser.supportedExtensions shouldBe emptySet()
            }
        }
    })
