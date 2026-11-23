@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.FailedScanPath
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.testing.testLibrary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.deleteIfExists
import kotlinx.io.files.Path as IoPath

/**
 * A scan diffs against the previous one, and every previous book absent from this pass becomes a
 * `Removed` that the persister tombstones. A book is absent for one of two reasons: it is gone, or
 * this pass could not read it. These tests drive a real [Scanner] over real folders twice and pin
 * that only the first reason ever produces a `Removed` — and that genuine deletions still do.
 */
class ScannerRemovalSafetyTest :
    FunSpec({

        fun noOpParser(): EmbeddedMetadataParser = EmbeddedMetadataParser(detector = AudioFormatDetector(), parsers = emptyList())

        fun scannerFor(vararg roots: Path): Scanner =
            Scanner(
                library = testLibrary(folders = roots.map { it.toString() }),
                metadataReader = AbsMetadataReader(contractJson),
                embeddedMetadataParser = noOpParser(),
                eventBus = MutableSharedFlow<ScanEvent>(replay = 64, extraBufferCapacity = 64),
                scanResultBus = MutableSharedFlow<ScanResult>(replay = 1, extraBufferCapacity = 64),
            )

        fun ScanResult.removedPaths(): List<String> = changes.filterIsInstance<ChangeEventDto.Removed>().map { it.rootRelPath }

        /** Leaves the book's folder on disk but with no audio in it — a book caught mid-replacement. */
        fun Path.replaceAudioWithNonAudio() {
            Files.list(this).use { files -> files.toList() }.forEach { it.deleteIfExists() }
            Files.createFile(resolve("notes.txt"))
            Files.createFile(resolve("cover.jpg"))
        }

        /** Runs [block] with [dir] unlistable, restoring it afterwards. False when this host cannot deny (root). */
        suspend fun withUnlistable(
            dir: Path,
            block: suspend () -> Unit,
        ): Boolean {
            if (System.getProperty("user.name") == "root") return false
            val original = Files.getPosixFilePermissions(dir)
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("---------"))
            try {
                if (Files.isReadable(dir)) return false
                block()
            } finally {
                Files.setPosixFilePermissions(dir, original)
            }
            return true
        }

        test("a full scan whose second pass cannot reach a folder root removes none of its books") {
            runTest {
                audioLibrary("listenup-189-a-").use { first ->
                    audioLibrary("listenup-189-b-").use { second ->
                        first.book("Author/One") { tracks(count = 1) }
                        second.book("Author/Two") { tracks(count = 1) }
                        val scanner = scannerFor(first.root, second.root)
                        scanner.runFullScan()

                        second.root.toFile().deleteRecursively()
                        val result = scanner.runFullScan()

                        result.removedPaths().shouldBeEmpty()
                        result.fullScanAuthoritative shouldBe false
                    }
                }
            }
        }

        test("a full scan does not remove a book whose analysis failed on the second pass") {
            runTest {
                audioLibrary("listenup-189-c-").use { lib ->
                    val book = lib.book("Author/Book") { tracks(count = 1) }
                    val scanner = scannerFor(lib.root)
                    scanner.runFullScan()

                    book.replaceAudioWithNonAudio()
                    val result = scanner.runFullScan()

                    result.removedPaths().shouldBeEmpty()
                    result.failedPaths shouldContain FailedScanPath(lib.root.toString(), "Author/Book")
                }
            }
        }

        test("an incremental scan does not remove a book whose analysis failed, and reports only its own failures") {
            runTest {
                audioLibrary("listenup-189-d-").use { lib ->
                    val book = lib.book("Author/Book") { tracks(count = 1) }
                    // Fails in the full scan, so the full scan's failedPaths name it.
                    lib.book("Other/Broken") { text("notes.txt") }
                    val scanner = scannerFor(lib.root)
                    scanner.runFullScan().failedPaths.map { it.rootRelPath } shouldContain "Other/Broken"

                    book.replaceAudioWithNonAudio()
                    val result = requireNotNull(scanner.runIncremental(IoPath(book.toString())))

                    result.removedPaths().shouldBeEmpty()
                    result.failedPaths shouldContainExactly listOf(FailedScanPath(lib.root.toString(), "Author/Book"))
                }
            }
        }

        test("an incremental scan whose folder root is unreachable is discarded and removes nothing") {
            runTest {
                audioLibrary("listenup-189-e-").use { lib ->
                    lib.book("Author/Book") { tracks(count = 1) }
                    val scanner = scannerFor(lib.root)
                    val before = scanner.runFullScan()

                    lib.root.toFile().deleteRecursively()
                    val result = scanner.runIncremental(IoPath(lib.root.toString()))

                    result.shouldBeNull()
                    requireNotNull(scanner.lastResult()).books.map { it.candidate.rootRelPath } shouldBe
                        before.books.map { it.candidate.rootRelPath }
                }
            }
        }

        test("an incremental scan still removes a book whose folder was deleted") {
            runTest {
                audioLibrary("listenup-189-f-").use { lib ->
                    val gone = lib.book("Author/Gone") { tracks(count = 1) }
                    lib.book("Author/Kept") { tracks(count = 1) }
                    val scanner = scannerFor(lib.root)
                    scanner.runFullScan()

                    gone.toFile().deleteRecursively()
                    val result = requireNotNull(scanner.runIncremental(IoPath(gone.toString())))

                    result.removedPaths() shouldContainExactly listOf("Author/Gone")
                }
            }
        }

        test("a full scan still removes a book whose folder was deleted") {
            runTest {
                audioLibrary("listenup-189-g-").use { lib ->
                    val gone = lib.book("Author/Gone") { tracks(count = 1) }
                    lib.book("Author/Kept") { tracks(count = 1) }
                    val scanner = scannerFor(lib.root)
                    scanner.runFullScan()

                    gone.toFile().deleteRecursively()
                    val result = scanner.runFullScan()

                    result.removedPaths() shouldContainExactly listOf("Author/Gone")
                }
            }
        }

        test("a full scan does not remove books under a directory it could not list, and says so") {
            runTest {
                audioLibrary("listenup-189-h-").use { lib ->
                    lib.book("Hidden Author/Book") { tracks(count = 1) }
                    lib.book("Visible Author/Book") { tracks(count = 1) }
                    val scanner = scannerFor(lib.root)
                    scanner.runFullScan()

                    withUnlistable(lib.root.resolve("Hidden Author")) {
                        val result = scanner.runFullScan()
                        result.removedPaths().shouldBeEmpty()
                        result.fullScanAuthoritative shouldBe false
                        result.errors.filterIsInstance<ScanError.FileUnreadable>().shouldNotBeEmpty()
                    }
                }
            }
        }

        test("an unlistable directory elsewhere in the folder does not hold back a genuine deletion") {
            runTest {
                audioLibrary("listenup-189-i-").use { lib ->
                    // A root-owned lost+found on a dedicated drive is the everyday case.
                    val lostAndFound = Files.createDirectories(lib.root.resolve("lost+found"))
                    val gone = lib.book("Author/Gone") { tracks(count = 1) }
                    lib.book("Author/Kept") { tracks(count = 1) }
                    val scanner = scannerFor(lib.root)
                    scanner.runFullScan()

                    gone.toFile().deleteRecursively()
                    withUnlistable(lostAndFound) {
                        val result = scanner.runFullScan()
                        result.removedPaths() shouldContainExactly listOf("Author/Gone")
                        // A filesystem's recovery folder is never a book: it does not switch the sweep off.
                        result.fullScanAuthoritative shouldBe true
                        result.errors.filterIsInstance<ScanError.FileUnreadable>().shouldBeEmpty()
                    }
                }
            }
        }
    })
