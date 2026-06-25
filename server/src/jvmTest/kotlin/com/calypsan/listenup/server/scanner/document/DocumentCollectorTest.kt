package com.calypsan.listenup.server.scanner.document

import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldMatch
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlinx.io.files.Path
import java.nio.file.Path as NioPath

/**
 * Unit tests for [DocumentCollector].
 *
 * Each test writes real files to a temp directory so that [Files.size] and the
 * SHA-256 hash actually compute against real bytes. The temp dir is deleted after
 * each test via [AutoCloseable].
 */
class DocumentCollectorTest :
    FunSpec({

        val collector = DocumentCollector()

        test("collects a PDF at the book root and ignores the audio file") {
            val tmpDir = Files.createTempDirectory("doc-collector-test-")
            val libraryRoot = tmpDir
            val bookRoot = tmpDir.resolve("MyBook").apply { createDirectories() }

            val audioFile = bookRoot.resolve("01 Track.mp3")
            Files.createFile(audioFile)
            val pdfFile = bookRoot.resolve("doc.pdf")
            val pdfBytes = "hello pdf".toByteArray()
            pdfFile.writeBytes(pdfBytes)

            try {
                val files =
                    listOf(
                        fileEntry(libraryRoot, bookRoot.resolve("01 Track.mp3"), FileType.AUDIO),
                        fileEntry(libraryRoot, pdfFile, FileType.EBOOK),
                    )

                val docs = collector.collect(Path(libraryRoot.toString()), Path(bookRoot.toString()), files)

                docs.size shouldBe 1
                val doc = docs.single()
                doc.relPath shouldBe "doc.pdf"
                doc.format shouldBe "pdf"
                doc.size shouldBe pdfBytes.size.toLong()
                doc.hash shouldBe sha256Hex(pdfBytes)
                doc.hash shouldHaveLength 64
                doc.hash shouldMatch Regex("[0-9a-f]{64}")
            } finally {
                tmpDir.toFile().deleteRecursively()
            }
        }

        test("collects a PDF in a subfolder with correct relative path") {
            val tmpDir = Files.createTempDirectory("doc-collector-test-")
            val libraryRoot = tmpDir
            val bookRoot = tmpDir.resolve("MyBook").apply { createDirectories() }
            val extrasDir = bookRoot.resolve("extras").apply { createDirectories() }

            val pdfFile = extrasDir.resolve("map.pdf")
            val pdfBytes = "map data".toByteArray()
            pdfFile.writeBytes(pdfBytes)

            // Also write an audio track so the Analyzer won't skip this book
            Files.createFile(bookRoot.resolve("01 Track.mp3"))

            try {
                val files =
                    listOf(
                        fileEntry(libraryRoot, bookRoot.resolve("01 Track.mp3"), FileType.AUDIO),
                        fileEntry(libraryRoot, pdfFile, FileType.EBOOK),
                    )

                val docs = collector.collect(Path(libraryRoot.toString()), Path(bookRoot.toString()), files)

                docs.size shouldBe 1
                val doc = docs.single()
                // Normalize to forward slashes for cross-platform assertion
                doc.relPath.replace('\\', '/') shouldBe "extras/map.pdf"
                doc.format shouldBe "pdf"
                doc.size shouldBe pdfBytes.size.toLong()
                doc.hash shouldBe sha256Hex(pdfBytes)
            } finally {
                tmpDir.toFile().deleteRecursively()
            }
        }

        test("returns empty list when file list contains no EBOOK files") {
            val tmpDir = Files.createTempDirectory("doc-collector-test-")
            val libraryRoot = tmpDir
            val bookRoot = tmpDir.resolve("MyBook").apply { createDirectories() }
            val audioFile = bookRoot.resolve("01 Track.mp3")
            Files.createFile(audioFile)

            try {
                val files =
                    listOf(
                        fileEntry(libraryRoot, audioFile, FileType.AUDIO),
                    )

                val docs = collector.collect(Path(libraryRoot.toString()), Path(bookRoot.toString()), files)

                docs.shouldBeEmpty()
            } finally {
                tmpDir.toFile().deleteRecursively()
            }
        }
    })

// --- helpers ---

private fun fileEntry(
    libraryRoot: NioPath,
    absolutePath: NioPath,
    fileType: FileType,
): FileEntry {
    val relPath = libraryRoot.relativize(absolutePath).toString().replace('\\', '/')
    return FileEntry(
        relPath = relPath,
        name = absolutePath.fileName.toString(),
        ext =
            absolutePath.fileName
                .toString()
                .substringAfterLast('.', "")
                .lowercase(),
        size = if (Files.exists(absolutePath)) Files.size(absolutePath) else 0L,
        mtimeMs = 0L,
        inode = null,
        fileType = fileType,
    )
}

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
