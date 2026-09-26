@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.CoverSource as ScanCoverSource
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedArtwork
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.cover.PendingCover
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path as IoPath

/**
 * Regression coverage for the rescan cover-wipe bug: a book whose files still carry cover art, but
 * whose cover could not be STORED this pass (no configured store, an unreadable file, or a throwing
 * store), must keep the cover it already had rather than have it nulled out. Only a scan that finds
 * no cover art at all (`analyzed.cover == null`) may legitimately clear the columns.
 *
 * Each test drives the real scan write path (`BookRepository.resolveOrInsert`, which delegates to
 * `upsertFromAnalyzed`) against a real migrated SQLite database and a real [CoverImageStore], so the
 * fix is exercised through production code, not a shortcut around it.
 */
class ScanCoverFailureTest :
    FunSpec({

        test("a rescan whose cover store throws keeps the book's cover") {
            withSqlDatabase {
                runTest {
                    val homeDir = Files.createTempDirectory("listenup-scan-cover-throws-")
                    try {
                        val repo = makeRepo(sql, driver, homeDir)
                        val registry = LibraryRegistry(sql)
                        val libId = registry.currentLibrary()
                        val folderId = FolderId("test-folder")

                        // (1) Seed the book with a real, valid EMBEDDED cover.
                        val originalBytes = fakeJpeg()
                        val seeded =
                            minimalBook(rootRelPath = "Author/Throws", title = "Original Title", cover = embeddedCover(originalBytes))
                        val seedPending = PendingCover(bytes = originalBytes, mime = "image/jpeg", source = CoverSource.EMBEDDED)
                        val insertResult = repo.resolveOrInsert(libId, folderId, seeded, seedPending)
                        val bookId = insertResult.shouldBeInstanceOf<AppResult.Success<IngestOutcome>>().data.bookId

                        val before = repo.findById(bookId).shouldNotBeNull()
                        val coverBefore = before.cover.shouldNotBeNull()
                        coverBefore.source shouldBe CoverSource.EMBEDDED
                        val rawBefore = rawCoverColumns(sql, bookId.value)

                        // (2) Re-scan: title changed (so this can't idempotent-skip on content alone),
                        // files still carry cover art, but the bytes fail ImageStore's magic-number
                        // sniff — the store call throws InvalidImageException internally, caught by
                        // ManagedCoverFiles.storeCoverIfPresent, which returns null.
                        val invalidBytes = "not an image".toByteArray()
                        val rescanned =
                            minimalBook(rootRelPath = "Author/Throws", title = "Changed Title", cover = embeddedCover(invalidBytes))
                        val rescanPending = PendingCover(bytes = invalidBytes, mime = "image/jpeg", source = CoverSource.EMBEDDED)
                        repo.resolveOrInsert(libId, folderId, rescanned, rescanPending)

                        // (3) The cover must be untouched (all three raw columns, not just the
                        // decoded source/hash); the title must have updated.
                        val after = repo.findById(bookId).shouldNotBeNull()
                        after.title shouldBe "Changed Title"
                        val coverAfter = after.cover.shouldNotBeNull()
                        coverAfter.source shouldBe coverBefore.source
                        coverAfter.hash shouldBe coverBefore.hash
                        rawCoverColumns(sql, bookId.value) shouldBe rawBefore
                    } finally {
                        homeDir.toFile().deleteRecursively()
                    }
                }
            }
        }

        test("a rescan whose cover file can't be read keeps the book's cover") {
            withSqlDatabase {
                runTest {
                    val homeDir = Files.createTempDirectory("listenup-scan-cover-unreadable-")
                    try {
                        val repo = makeRepo(sql, driver, homeDir)
                        val registry = LibraryRegistry(sql)
                        val libId = registry.currentLibrary()
                        val folderId = FolderId("test-folder")

                        val originalBytes = fakeJpeg()
                        val seeded =
                            minimalBook(rootRelPath = "Author/Unreadable", title = "Original Title", cover = embeddedCover(originalBytes))
                        val seedPending = PendingCover(bytes = originalBytes, mime = "image/jpeg", source = CoverSource.EMBEDDED)
                        val insertResult = repo.resolveOrInsert(libId, folderId, seeded, seedPending)
                        val bookId = insertResult.shouldBeInstanceOf<AppResult.Success<IngestOutcome>>().data.bookId
                        val coverBefore =
                            repo
                                .findById(bookId)
                                .shouldNotBeNull()
                                .cover
                                .shouldNotBeNull()

                        // Re-scan: content changed, files still say there's cover art (a Filesystem
                        // source pointing at a file that no longer exists), but the read failed
                        // upstream — modeled here by handing upsertFromAnalyzed a null pendingCover
                        // alongside a non-null analyzed.cover, exactly as BookPersister's
                        // extractPendingCover would when the read throws.
                        val rescanned =
                            minimalBook(
                                rootRelPath = "Author/Unreadable",
                                title = "Changed Title",
                                cover =
                                    ScanCoverSource.Filesystem(
                                        file =
                                            FileEntry(
                                                relPath = "Author/Unreadable/cover.jpg",
                                                name = "cover.jpg",
                                                ext = "jpg",
                                                size = 0L,
                                                mtimeMs = 0L,
                                                inode = null,
                                                fileType = FileType.IMAGE,
                                            ),
                                    ),
                            )
                        repo.resolveOrInsert(libId, folderId, rescanned, pendingCover = null)

                        val after = repo.findById(bookId).shouldNotBeNull()
                        after.title shouldBe "Changed Title"
                        val coverAfter = after.cover.shouldNotBeNull()
                        coverAfter.source shouldBe coverBefore.source
                        coverAfter.hash shouldBe coverBefore.hash
                    } finally {
                        homeDir.toFile().deleteRecursively()
                    }
                }
            }
        }

        test("a rescan with no cover store configured keeps the book's cover") {
            withSqlDatabase {
                runTest {
                    val homeDir = Files.createTempDirectory("listenup-scan-cover-unconfigured-")
                    try {
                        val configuredRepo = makeRepo(sql, driver, homeDir)
                        val registry = LibraryRegistry(sql)
                        val libId = registry.currentLibrary()
                        val folderId = FolderId("test-folder")

                        val originalBytes = fakeJpeg()
                        val seeded =
                            minimalBook(rootRelPath = "Author/Unconfigured", title = "Original Title", cover = embeddedCover(originalBytes))
                        val seedPending = PendingCover(bytes = originalBytes, mime = "image/jpeg", source = CoverSource.EMBEDDED)
                        val insertResult = configuredRepo.resolveOrInsert(libId, folderId, seeded, seedPending)
                        val bookId = insertResult.shouldBeInstanceOf<AppResult.Success<IngestOutcome>>().data.bookId
                        val coverBefore =
                            configuredRepo
                                .findById(bookId)
                                .shouldNotBeNull()
                                .cover
                                .shouldNotBeNull()

                        // Re-scan through a repo whose cover store is unconfigured (coverImageStore =
                        // null, the default) — mirrors a server running with no library cover path set.
                        val unconfiguredRepo = makeRepo(sql, driver, homeDir = null, configureCoverStore = false)
                        val rescanned =
                            minimalBook(rootRelPath = "Author/Unconfigured", title = "Changed Title", cover = embeddedCover(originalBytes))
                        unconfiguredRepo.resolveOrInsert(libId, folderId, rescanned, pendingCover = null)

                        val after = unconfiguredRepo.findById(bookId).shouldNotBeNull()
                        after.title shouldBe "Changed Title"
                        val coverAfter = after.cover.shouldNotBeNull()
                        coverAfter.source shouldBe coverBefore.source
                        coverAfter.hash shouldBe coverBefore.hash
                    } finally {
                        homeDir.toFile().deleteRecursively()
                    }
                }
            }
        }

        test("a rescan of files that no longer have cover art clears the cover") {
            withSqlDatabase {
                runTest {
                    val homeDir = Files.createTempDirectory("listenup-scan-cover-no-art-")
                    try {
                        val repo = makeRepo(sql, driver, homeDir)
                        val registry = LibraryRegistry(sql)
                        val libId = registry.currentLibrary()
                        val folderId = FolderId("test-folder")

                        val originalBytes = fakeJpeg()
                        val seeded =
                            minimalBook(rootRelPath = "Author/NoArt", title = "Original Title", cover = embeddedCover(originalBytes))
                        val seedPending = PendingCover(bytes = originalBytes, mime = "image/jpeg", source = CoverSource.EMBEDDED)
                        val insertResult = repo.resolveOrInsert(libId, folderId, seeded, seedPending)
                        val bookId = insertResult.shouldBeInstanceOf<AppResult.Success<IngestOutcome>>().data.bookId
                        repo
                            .findById(bookId)
                            .shouldNotBeNull()
                            .cover
                            .shouldNotBeNull()

                        // Re-scan: the files genuinely no longer carry any cover art.
                        val rescanned = minimalBook(rootRelPath = "Author/NoArt", title = "Changed Title", cover = null)
                        repo.resolveOrInsert(libId, folderId, rescanned, pendingCover = null)

                        val after = repo.findById(bookId).shouldNotBeNull()
                        after.title shouldBe "Changed Title"
                        after.cover.shouldBeNull()
                        val row = sql.booksQueries.selectById(bookId.value).executeAsOne()
                        row.cover_source.shouldBeNull()
                        row.cover_path.shouldBeNull()
                        row.cover_hash.shouldBeNull()
                    } finally {
                        homeDir.toFile().deleteRecursively()
                    }
                }
            }
        }

        test("an otherwise-identical rescan whose cover can't be stored writes nothing") {
            withSqlDatabase {
                runTest {
                    val homeDir = Files.createTempDirectory("listenup-scan-cover-skip-")
                    try {
                        val repo = makeRepo(sql, driver, homeDir)
                        val registry = LibraryRegistry(sql)
                        val libId = registry.currentLibrary()
                        val folderId = FolderId("test-folder")

                        val originalBytes = fakeJpeg()
                        val seeded = minimalBook(rootRelPath = "Author/Skip", title = "Same Title", cover = embeddedCover(originalBytes))
                        val seedPending = PendingCover(bytes = originalBytes, mime = "image/jpeg", source = CoverSource.EMBEDDED)
                        val insertResult = repo.resolveOrInsert(libId, folderId, seeded, seedPending)
                        val bookId = insertResult.shouldBeInstanceOf<AppResult.Success<IngestOutcome>>().data.bookId

                        val revisionBefore =
                            sql.booksQueries
                                .selectById(bookId.value)
                                .executeAsOneOrNull()
                                ?.revision
                                ?: error("book ${bookId.value} not found")

                        // Re-scan: IDENTICAL content (same AnalyzedBook), but the cover read fails this
                        // pass (pendingCover null alongside a non-null analyzed.cover) — must skip
                        // entirely: no revision bump, no write.
                        repo.resolveOrInsert(libId, folderId, seeded, pendingCover = null)

                        val revisionAfter =
                            sql.booksQueries
                                .selectById(bookId.value)
                                .executeAsOneOrNull()
                                ?.revision
                                ?: error("book ${bookId.value} not found after rescan")
                        revisionAfter shouldBe revisionBefore

                        val after = repo.findById(bookId).shouldNotBeNull()
                        val coverAfter = after.cover.shouldNotBeNull()
                        coverAfter.source shouldBe CoverSource.EMBEDDED
                    } finally {
                        homeDir.toFile().deleteRecursively()
                    }
                }
            }
        }
    })

// --- Fixtures ---------------------------------------------------------------

private fun embeddedCover(bytes: ByteArray): ScanCoverSource =
    ScanCoverSource.Embedded(artwork = EmbeddedArtwork(mime = "image/jpeg", bytes = bytes))

/** Minimal valid JPEG magic bytes — passes [ImageStore]'s magic-number sniff. */
private fun fakeJpeg(): ByteArray =
    byteArrayOf(
        0xFF.toByte(),
        0xD8.toByte(),
        0xFF.toByte(),
        0xE0.toByte(),
        0x00,
        0x10,
        'J'.code.toByte(),
        'F'.code.toByte(),
    )

/** Minimal [AnalyzedBook] with one audio track and an optional cover signal. */
private fun minimalBook(
    rootRelPath: String,
    title: String,
    cover: ScanCoverSource?,
): AnalyzedBook {
    val file =
        FileEntry(
            relPath = "$rootRelPath/01.m4b",
            name = "01.m4b",
            ext = "m4b",
            size = 1024L,
            mtimeMs = 0L,
            inode = null,
            fileType = FileType.AUDIO,
        )
    return AnalyzedBook(
        candidate = CandidateBook(rootRelPath = rootRelPath, isFile = false, files = listOf(file)),
        title = title,
        tracks = listOf(TrackEntry(file = file)),
        cover = cover,
    )
}

private data class RawCoverColumns(
    val source: String?,
    val path: String?,
    val hash: String?,
)

private fun rawCoverColumns(
    sql: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase,
    bookId: String,
): RawCoverColumns {
    val row = sql.booksQueries.selectById(bookId).executeAsOne()
    return RawCoverColumns(row.cover_source, row.cover_path, row.cover_hash)
}

private const val MAX_COVER_BYTES = 10L * 1024 * 1024

private fun makeRepo(
    sql: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase,
    driver: app.cash.sqldelight.db.SqlDriver,
    homeDir: java.nio.file.Path?,
    configureCoverStore: Boolean = true,
): BookRepository {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val coverStore =
        if (configureCoverStore && homeDir != null) {
            CoverImageStore(ImageStore(IoPath(homeDir.resolve("covers").toString()), MAX_COVER_BYTES))
        } else {
            null
        }
    return BookRepository(
        db = sql,
        driver = driver,
        bus = bus,
        registry = syncRegistry,
        contributorRepository = ContributorRepository(sql, bus, syncRegistry),
        seriesRepository = SeriesRepository(sql, bus, syncRegistry),
        genreRepository = GenreRepository(sql, bus, syncRegistry),
        coverImageStore = coverStore,
        homeDir = homeDir?.let { IoPath(it.toString()) },
    )
}
