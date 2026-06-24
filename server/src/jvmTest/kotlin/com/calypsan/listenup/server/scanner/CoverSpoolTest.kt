package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.CoverSource
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.AudioTags
import com.calypsan.listenup.domain.embeddedmeta.ChapterSource
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedArtwork
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlinx.io.files.Path as IoPath

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

private val COVER_BYTES = byteArrayOf(1, 2, 3)
private val ARTWORK_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) // fake JPEG magic
private const val COVER_MIME = "image/jpeg"
private const val ARTWORK_MIME = "image/png"

private fun minimalEmbedded(artworkBytes: ByteArray? = null): EmbeddedAudioMetadata =
    EmbeddedAudioMetadata(
        format = AudioFormat.Mp3,
        durationMs = 60_000L,
        tags =
            AudioTags(
                title = null,
                subtitle = null,
                authors = emptyList(),
                narrators = emptyList(),
                series = emptyList(),
                genres = emptyList(),
                description = null,
                publisher = null,
                publishedYear = null,
                asin = null,
                isbn = null,
                language = null,
                trackNumber = null,
                discNumber = null,
                custom = emptyMap(),
            ),
        chapters = emptyList(),
        chaptersSource = ChapterSource.None,
        artwork = artworkBytes?.let { EmbeddedArtwork(mime = ARTWORK_MIME, bytes = it) },
    )

private fun bookWithEmbeddedCover(
    rootRelPath: String,
    bytes: ByteArray,
    embedded: EmbeddedAudioMetadata? = null,
) = AnalyzedBook(
    candidate = CandidateBook(rootRelPath = rootRelPath, isFile = false, files = emptyList()),
    title = rootRelPath,
    cover = CoverSource.Embedded(EmbeddedArtwork(mime = COVER_MIME, bytes = bytes)),
    embedded = embedded,
)

private fun bookWithFilesystemCover(
    rootRelPath: String,
    embedded: EmbeddedAudioMetadata? = null,
) = AnalyzedBook(
    candidate = CandidateBook(rootRelPath = rootRelPath, isFile = false, files = emptyList()),
    title = rootRelPath,
    cover =
        CoverSource.Filesystem(
            file =
                FileEntry(
                    relPath = "$rootRelPath/cover.jpg",
                    name = "cover.jpg",
                    ext = "jpg",
                    size = 1024L,
                    mtimeMs = 0L,
                    fileType = FileType.IMAGE,
                ),
        ),
    embedded = embedded,
)

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

class CoverSpoolTest :
    FunSpec({

        // -------------------------------------------------------------------
        // Core spool behaviour — embedded cover
        // -------------------------------------------------------------------

        test("spoolCover writes embedded bytes to disk and returns a Spooled ref") {
            val root = Files.createTempDirectory("spool-test")
            val spool = CoverSpool(IoPath(root.toString()))
            val out = spool.spoolCover("scan1", bookWithEmbeddedCover("A/B", COVER_BYTES))

            val cover = out.cover.shouldBeInstanceOf<CoverSource.Spooled>()
            spool.read(cover) shouldBe COVER_BYTES
        }

        test("spoolCover: embedded-metadata book — artwork emptied, marker + mime kept, originals spooled") {
            val root = Files.createTempDirectory("spool-test")
            val spool = CoverSpool(IoPath(root.toString()))
            val book =
                bookWithEmbeddedCover(
                    rootRelPath = "A/B",
                    bytes = COVER_BYTES,
                    embedded = minimalEmbedded(artworkBytes = ARTWORK_BYTES),
                )

            val out = spool.spoolCover("scan1", book)

            // Cover spooled successfully — original bytes written to disk
            val spooled = out.cover.shouldBeInstanceOf<CoverSource.Spooled>()
            spool.read(spooled) shouldBe COVER_BYTES

            // embedded.artwork MARKER is kept (non-null) — the "w/artwork" counter must stay valid
            out.embedded?.artwork shouldNotBe null

            // bytes are emptied (defect 1 fix: redundant bytes freed)
            out.embedded!!
                .artwork!!
                .bytes
                .isEmpty()
                .shouldBeTrue()

            // mime is preserved on the lightened marker
            out.embedded!!.artwork!!.mime shouldBe ARTWORK_MIME
        }

        // -------------------------------------------------------------------
        // Defect 1 gap: filesystem-cover book WITH embedded artwork
        // -------------------------------------------------------------------

        test("spoolCover — filesystem-cover book WITH embedded artwork: artwork bytes emptied, marker kept, filesystem cover unchanged") {
            val root = Files.createTempDirectory("spool-test")
            val spool = CoverSpool(IoPath(root.toString()))
            val book =
                bookWithFilesystemCover(
                    rootRelPath = "A/B",
                    embedded = minimalEmbedded(artworkBytes = ARTWORK_BYTES),
                )

            val out = spool.spoolCover("scan1", book)

            // Filesystem cover is UNCHANGED
            out.cover.shouldBeInstanceOf<CoverSource.Filesystem>()
            out.cover shouldBe book.cover

            // Defect 1 fix: artwork bytes freed even though this book used a filesystem cover
            out.embedded?.artwork shouldNotBe null
            out.embedded!!
                .artwork!!
                .bytes
                .isEmpty()
                .shouldBeTrue()
            out.embedded!!.artwork!!.mime shouldBe ARTWORK_MIME
        }

        // -------------------------------------------------------------------
        // No-op paths
        // -------------------------------------------------------------------

        test("spoolCover leaves a no-cover book with no embedded metadata unchanged") {
            val spool = CoverSpool(IoPath(Files.createTempDirectory("spool-test").toString()))
            val book =
                AnalyzedBook(
                    candidate = CandidateBook(rootRelPath = "A", isFile = false, files = emptyList()),
                    title = "A",
                )
            spool.spoolCover("scan1", book) shouldBe book
        }

        test("spoolCover leaves a filesystem-cover book with no embedded metadata unchanged") {
            val spool = CoverSpool(IoPath(Files.createTempDirectory("spool-test").toString()))
            val book = bookWithFilesystemCover("A/B", embedded = null)
            spool.spoolCover("scan1", book) shouldBe book
        }

        test("spoolCover does not touch an embedded.artwork that already has empty bytes") {
            val root = Files.createTempDirectory("spool-test")
            val spool = CoverSpool(IoPath(root.toString()))
            val book = bookWithFilesystemCover("A/B", embedded = minimalEmbedded(artworkBytes = ByteArray(0)))
            // already-empty bytes → returned as-is (no unnecessary copy)
            val out = spool.spoolCover("scan1", book)
            out.embedded shouldBe book.embedded
        }

        // -------------------------------------------------------------------
        // Lifecycle: clearScan / sweepOrphans
        // -------------------------------------------------------------------

        test("clearScan removes the scan's dir; sweepOrphans removes STALE leftover dirs") {
            val root = Files.createTempDirectory("spool-test")
            val spool = CoverSpool(IoPath(root.toString()))
            spool.spoolCover("scanA", bookWithEmbeddedCover("A", byteArrayOf(9)))
            spool.spoolCover("scanB", bookWithEmbeddedCover("B", byteArrayOf(8)))
            spool.clearScan("scanA")
            root.resolve("scanA").exists() shouldBe false
            root.resolve("scanB").exists() shouldBe true
            // Age scanB past the orphan grace so the sweep treats it as a crashed-scan leftover.
            Files.setLastModifiedTime(
                root.resolve("scanB"),
                FileTime.fromMillis(System.currentTimeMillis() - CoverSpool.ORPHAN_GRACE_MS - 60_000L),
            )
            spool.sweepOrphans()
            root.resolve("scanB").exists() shouldBe false
        }

        test("sweepOrphans preserves recently-modified dirs — a live scan is never clobbered") {
            val root = Files.createTempDirectory("spool-test")
            val spool = CoverSpool(IoPath(root.toString()))

            // A just-created dir stands in for a concurrent (possibly other-process) live scan.
            val liveDir = root.resolve("live-scan-corr")
            Files.createDirectories(liveDir)

            val crashedDir = root.resolve("crashed-scan-corr")
            Files.createDirectories(crashedDir)
            Files.setLastModifiedTime(
                crashedDir,
                FileTime.fromMillis(System.currentTimeMillis() - 2 * 60 * 60 * 1000L), // 2h ago
            )

            spool.sweepOrphans()

            withClue("a recently-written (live) scan dir must survive the startup sweep") {
                liveDir.exists() shouldBe true
            }
            withClue("a long-stale (crashed) scan dir must be swept") {
                crashedDir.exists() shouldBe false
            }
        }

        // -------------------------------------------------------------------
        // Write-failure path
        // -------------------------------------------------------------------

        test("a write failure keeps the cover in memory but still empties embedded artwork bytes") {
            val file = Files.createTempFile("not-a-dir", "") // root is a FILE → createDirectories fails
            val spool = CoverSpool(IoPath(file.toString()))
            val originalBytes = byteArrayOf(1)
            val book =
                bookWithEmbeddedCover(
                    rootRelPath = "A",
                    bytes = originalBytes,
                    embedded = minimalEmbedded(artworkBytes = ARTWORK_BYTES),
                )

            val out = spool.spoolCover("scan1", book)

            // Cover is still Embedded — disk write failed so we kept it in memory
            out.cover.shouldBeInstanceOf<CoverSource.Embedded>()
            (out.cover as CoverSource.Embedded).artwork.bytes shouldBe originalBytes

            // Embedded artwork bytes ARE still emptied (heap savings preserved even on failure)
            out.embedded?.artwork shouldNotBe null
            out.embedded!!
                .artwork!!
                .bytes
                .isEmpty()
                .shouldBeTrue()
        }

        test("a write failure on a book without embedded metadata returns cover unchanged") {
            val file = Files.createTempFile("not-a-dir", "")
            val spool = CoverSpool(IoPath(file.toString()))
            val book = bookWithEmbeddedCover("A", byteArrayOf(1))
            // no embedded metadata — result is byte-for-byte equal to input
            spool.spoolCover("scan1", book) shouldBe book
        }
    })
