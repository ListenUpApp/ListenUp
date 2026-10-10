package com.calypsan.listenup.server.cover

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.io.files.Path as IoPath

class CoverContentResolverTest :
    FunSpec({

        fun resolver() =
            CoverContentResolver(
                cache = EmbeddedCoverCache(),
                parser = EmbeddedMetadataParser(detector = AudioFormatDetector(), parsers = emptyList()),
            )

        test("a filesystem cover larger than the library cover limit yields no content") {
            runTest {
                val dir = Files.createTempDirectory("listenup-cover-oversize-")
                try {
                    val file = dir.resolve("cover.jpg")
                    // A sparse file: the size is what the cap judges, so no 10 MiB is actually written.
                    RandomAccessFile(file.toFile(), "rw").use { it.setLength(LIBRARY_COVER_MAX_BYTES + 1) }

                    resolver()
                        .content(BookId("b1"), CoverInfo.Filesystem(IoPath(file.toString()), hash = null))
                        .shouldBeNull()
                } finally {
                    dir.toFile().deleteRecursively()
                }
            }
        }

        test("a filesystem cover within the library cover limit is served with its bytes") {
            runTest {
                val dir = Files.createTempDirectory("listenup-cover-ok-")
                try {
                    val file = dir.resolve("cover.jpg")
                    Files.write(file, byteArrayOf(1, 2, 3))

                    val content =
                        resolver().content(BookId("b1"), CoverInfo.Filesystem(IoPath(file.toString()), hash = null))

                    content.shouldNotBeNull()
                    content.bytes.toList() shouldBe listOf<Byte>(1, 2, 3)
                } finally {
                    dir.toFile().deleteRecursively()
                }
            }
        }
    })
