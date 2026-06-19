package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.CoverSource
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedArtwork
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlin.io.path.exists

private fun bookWithEmbeddedCover(
    rootRelPath: String,
    bytes: ByteArray,
) = AnalyzedBook(
    candidate = CandidateBook(rootRelPath = rootRelPath, isFile = false, files = emptyList()),
    title = rootRelPath,
    cover = CoverSource.Embedded(EmbeddedArtwork(mime = "image/jpeg", bytes = bytes)),
)

class CoverSpoolTest :
    FunSpec({
        test("spoolCover writes embedded bytes to disk and returns a Spooled ref with no in-memory bytes") {
            val root = Files.createTempDirectory("spool-test")
            val spool = CoverSpool(root)
            val out = spool.spoolCover("scan1", bookWithEmbeddedCover("A/B", byteArrayOf(1, 2, 3)))

            val cover = out.cover.shouldBeInstanceOf<CoverSource.Spooled>()
            out.embedded?.artwork shouldBe null
            spool.read(cover) shouldBe byteArrayOf(1, 2, 3)
        }

        test("spoolCover leaves a filesystem/no cover book unchanged") {
            val spool = CoverSpool(Files.createTempDirectory("spool-test"))
            val book =
                AnalyzedBook(
                    candidate = CandidateBook(rootRelPath = "A", isFile = false, files = emptyList()),
                    title = "A",
                )
            spool.spoolCover("scan1", book) shouldBe book
        }

        test("clearScan removes the scan's dir; sweepOrphans removes all leftover dirs") {
            val root = Files.createTempDirectory("spool-test")
            val spool = CoverSpool(root)
            spool.spoolCover("scanA", bookWithEmbeddedCover("A", byteArrayOf(9)))
            spool.spoolCover("scanB", bookWithEmbeddedCover("B", byteArrayOf(8)))
            spool.clearScan("scanA")
            root.resolve("scanA").exists() shouldBe false
            root.resolve("scanB").exists() shouldBe true
            spool.sweepOrphans()
            root.resolve("scanB").exists() shouldBe false
        }

        test("a write failure returns the book unchanged (cover preserved in memory)") {
            val file = Files.createTempFile("not-a-dir", "") // root is a FILE → createDirectories fails
            val spool = CoverSpool(file)
            val book = bookWithEmbeddedCover("A", byteArrayOf(1))
            spool.spoolCover("scan1", book) shouldBe book
        }
    })
