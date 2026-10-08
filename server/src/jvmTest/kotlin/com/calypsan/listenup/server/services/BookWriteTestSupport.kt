package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.BookServiceImpl
import com.calypsan.listenup.server.api.ContributorServiceImpl
import com.calypsan.listenup.server.api.SeriesServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.PermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.cover.CoverStorage
import com.calypsan.listenup.server.librarywrite.testBroker
import com.calypsan.listenup.server.cover.PendingCover
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path as IoPath

/*
 * Shared rig for book write-path tests that need a real managed cover: a temp-dir cover store, a
 * book seeded through the real scan write, and the repository/service pair built over them.
 */

internal const val MANAGED_COVER_TEST_MAX_BYTES = 10L * 1024 * 1024

/** Minimal valid JPEG magic bytes — passes [ImageStore]'s magic-number sniff. */
internal fun fakeCoverBytes(): ByteArray =
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

/** Minimal [AnalyzedBook] with one audio track — no cover; the cover rides in as a [PendingCover]. */
internal fun coverPathAnalyzedBook(rootRelPath: String): AnalyzedBook {
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
        title = rootRelPath.substringAfterLast('/'),
        tracks = listOf(TrackEntry(file = file)),
    )
}

/**
 * Builds a real [CoverImageStore] backed by a fresh temp directory and hands it to [block] along
 * with the [kotlinx.io.files.Path] home dir [BookRepository] needs — cleans the directory up
 * afterwards regardless of outcome. Mirrors the rig [BookPersistCoverTest] uses to seed a managed
 * cover through the real scan write path.
 */
internal inline fun withCoverStore(block: (CoverImageStore, IoPath) -> Unit) {
    val homeDir = Files.createTempDirectory("listenup-cover-path-test-")
    try {
        val coverStore =
            CoverImageStore(ImageStore(IoPath(homeDir.resolve("covers").toString()), MANAGED_COVER_TEST_MAX_BYTES))
        block(coverStore, IoPath(homeDir.toString()))
    } finally {
        homeDir.toFile().deleteRecursively()
    }
}

/**
 * Seeds a single book with a managed cover for [source], returning its id plus the `cover_path`
 * that was recorded.
 *
 * [CoverSource.FILESYSTEM] and [CoverSource.EMBEDDED] are seeded through the real scan write path —
 * [BookRepository.resolveOrInsert] with a [PendingCover] — exactly what [BookPersister] does on a
 * scan; the stored path comes from production code, not a test double. [repo] must have been built
 * with a real [CoverImageStore] + home dir (see [withCoverStore]) for that write to land a file.
 *
 * Nothing in production writes [CoverSource.ENRICHED] today (metadata-apply writes UPLOADED; the
 * scanner writes FILESYSTEM/EMBEDDED) — [BookRepository.setManagedCover] is the closest real seam,
 * the same one an UPLOADED cover goes through, so ENRICHED seeds through that instead.
 */
internal suspend fun seedManagedCover(
    repo: BookRepository,
    db: SqlTestDatabases,
    source: CoverSource,
): Pair<BookId, String> =
    if (source == CoverSource.FILESYSTEM || source == CoverSource.EMBEDDED) {
        val libraryId = LibraryRegistry(db.sql).currentLibrary()
        val analyzed = coverPathAnalyzedBook("Author/${source.name}")
        val pending = PendingCover(bytes = fakeCoverBytes(), mime = "image/jpeg", source = source)
        val result = repo.resolveOrInsert(libraryId, FolderId("test-folder"), analyzed, pending)
        val bookId = result.shouldBeInstanceOf<AppResult.Success<IngestOutcome>>().data.bookId
        val path =
            db.sql.booksQueries
                .selectById(bookId.value)
                .executeAsOne()
                .cover_path
                ?: error("expected a managed cover path after resolveOrInsert")
        bookId to path
    } else {
        val bookId = BookId("b-${source.name.lowercase()}")
        val hash = "hash-${source.name}"
        repo.upsert(
            bookPayloadFixture(
                id = bookId.value,
                title = "Seed Title",
                cover = CoverPayload(source = source, hash = hash),
            ),
        )
        val relPath = "covers/${bookId.value}/cover.jpg"
        repo
            .setManagedCover(id = bookId, relPath = relPath, hash = hash, source = source)
            .shouldBeInstanceOf<AppResult.Success<Unit>>()
        bookId to relPath
    }

internal fun makeBookRepo(
    db: SqlTestDatabases,
    coverImageStore: CoverImageStore? = null,
    homeDir: IoPath? = null,
): BookRepository {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    return BookRepository(
        db = db.sql,
        driver = db.driver,
        bus = bus,
        registry = syncRegistry,
        contributorRepository = ContributorRepository(db.sql, bus, syncRegistry),
        seriesRepository = SeriesRepository(db.sql, bus, syncRegistry),
        genreRepository = GenreRepository(db.sql, bus, syncRegistry),
        coverImageStore = coverImageStore,
        homeDir = homeDir,
    )
}

/** Mirrors [com.calypsan.listenup.server.api.BookServiceImplUpdateTest]'s `bookServiceFor` helper. */
internal fun makeBookServiceAndRepo(
    db: SqlTestDatabases,
    coverImageStore: CoverImageStore? = null,
    homeDir: IoPath? = null,
): Pair<BookServiceImpl, BookRepository> {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val contributorRepo = ContributorRepository(db.sql, bus, syncRegistry)
    val seriesRepo = SeriesRepository(db.sql, bus, syncRegistry)
    val genreRepo = GenreRepository(db.sql, bus, syncRegistry)
    val repo =
        BookRepository(
            db = db.sql,
            driver = db.driver,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository = genreRepo,
            coverImageStore = coverImageStore,
            homeDir = homeDir,
        )
    val service =
        BookServiceImpl(
            repo = repo,
            contributorRepo = contributorRepo,
            seriesRepo = seriesRepo,
            coverStorage = CoverStorage(testBroker()),
            sql = db.sql,
            genreRepo = genreRepo,
            accessPolicy = BookAccessPolicy(db.sql, db.driver),
            permissionPolicy = PermissionPolicy(db.sql),
            principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
        )
    return service to repo
}
