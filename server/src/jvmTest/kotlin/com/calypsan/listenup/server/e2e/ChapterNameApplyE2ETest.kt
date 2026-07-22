@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.Mutated
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.api.MetadataImageDeps
import com.calypsan.listenup.server.api.MetadataLookupServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.ProductTag
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.services.CoverSearchService
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.testCoordinator
import com.calypsan.listenup.server.testing.testEnrichmentDeps
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

private val TEST_NOW = Instant.parse("2026-06-05T12:00:00Z")
private const val ASIN = "B0CHAPTERS"

/**
 * Tier 2 E2E test for the chapter-name apply path
 * ([MetadataLookupServiceImpl.applyChapterNames]).
 *
 * Exercises the full gated service call chain with a ROOT principal:
 *  - A stubbed [AudibleApi] returning a canned chapter list.
 *  - Real [BookRepository] (+ [ContributorRepository], [SeriesRepository])
 *    against an in-memory SQLite database.
 *  - Real [MetadataCacheRepository] + [MetadataService] so chapter caching is
 *    exercised, not bypassed.
 *
 * Proves the crown-jewel invariants of the feature:
 *  - A subset rename touches only the selected ordinals' `title`, preserving
 *    every chapter's `startTime`/`duration` and the unselected names.
 *  - A mismatched-count edition is refused with
 *    [MetadataError.ChapterCountMismatch] and writes nothing (revision unchanged).
 */
class ChapterNameApplyE2ETest :
    FunSpec({

        test("subset rename: only selected names change, all timestamps preserved") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val ctx = wire(this, audible("Prologue", "Chapter One", "Chapter Two"))
                runTest {
                    ctx.bookRepo.upsert(
                        bookWith("e2e-book", local("Track 1", "Track 2", "Track 3")),
                        clientOpId = null,
                    )

                    val result =
                        ctx.service.applyChapterNames(BookId("e2e-book"), ASIN, MetadataLocale("us"), setOf(0, 2))

                    val success = result.shouldBeInstanceOf<AppResult.Success<Mutated<Unit>>>()
                    // Echo-in-response: the response carries the book's own frame (captured via
                    // withCapturedFrames) so the originating device applies the rename read-your-writes.
                    success.data.frames shouldHaveSize 1
                    success.data.frames
                        .single()
                        .domain shouldBe ctx.bookRepo.domainName
                    val after =
                        ctx.bookRepo
                            .findById(BookId("e2e-book"))
                            .shouldNotBeNull()
                            .chapters
                            .sortedBy { it.startTime }
                    after.map { it.title } shouldBe listOf("Prologue", "Track 2", "Chapter Two")
                    after.map { it.startTime } shouldBe listOf(0L, 1000L, 2000L)
                    after.map { it.duration } shouldBe listOf(1000L, 1000L, 1000L)
                }
            }
        }

        test("mismatched edition: ChapterCountMismatch, book unchanged") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val ctx = wire(this, audible("A", "B", "C", "D", "E"))
                runTest {
                    ctx.bookRepo.upsert(bookWith("e2e-book", local("Track 1", "Track 2", "Track 3")), clientOpId = null)
                    val revBefore =
                        ctx.bookRepo
                            .findById(BookId("e2e-book"))
                            .shouldNotBeNull()
                            .revision

                    val result =
                        ctx.service.applyChapterNames(BookId("e2e-book"), ASIN, MetadataLocale("us"), setOf(0, 1, 2))

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<MetadataError.ChapterCountMismatch>()
                    val after = ctx.bookRepo.findById(BookId("e2e-book")).shouldNotBeNull()
                    after.chapters.map { it.title } shouldBe listOf("Track 1", "Track 2", "Track 3")
                    after.revision shouldBe revBefore
                }
            }
        }
    })

// ─── helpers ─────────────────────────────────────────────────────────────────

private data class Ctx(
    val bookRepo: BookRepository,
    val service: MetadataLookupServiceImpl,
)

private fun wire(
    dbs: SqlTestDatabases,
    chapters: List<AudibleChapter>,
): Ctx {
    val tempDir = Files.createTempDirectory("chapter-e2e-").also { it.toFile().deleteOnExit() }
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val contributorRepo = ContributorRepository(dbs.sql, bus, registry)
    val seriesRepo = SeriesRepository(dbs.sql, bus, registry)
    val genreRepo = GenreRepository(dbs.sql, bus, registry)
    val bookRepo = BookRepository(dbs.sql, bus, registry, dbs.driver, contributorRepo, seriesRepo, genreRepo)
    val metadataService =
        MetadataService(
            audible = ChapterFakeAudibleApi(chapters),
            itunes = NoOpITunes(),
            cache = MetadataCacheRepository(dbs.sql, clock = FixedClock(TEST_NOW)),
        )
    val service =
        MetadataLookupServiceImpl(
            metadataService = metadataService,
            coordinator = testCoordinator(metadataService),
            coverSearchService =
                CoverSearchService(
                    readBook = { null },
                    registry = MetadataProviderRegistry(emptyList()),
                    probeDimensions = { null },
                ),
            bookRepository = bookRepo,
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            imageDeps =
                MetadataImageDeps(
                    imageStorage = ImageStorage(HttpClient(MockEngine { _ -> respond("", HttpStatusCode.OK) })),
                    coverImageStore =
                        CoverImageStore(ImageStore(Path(tempDir.resolve("covers").toString()), 10L * 1024 * 1024)),
                    imageHome = Path(tempDir.toString()),
                ),
            enrichmentDeps = testEnrichmentDeps(dbs.sql, bus, registry),
            permissionPolicy = UserPermissionPolicy(dbs.sql),
            sqlDb = dbs.sql,
            genreRepository = genreRepo,
            principal = PrincipalProvider { UserPrincipal(UserId("root"), SessionId("s"), UserRole.ROOT) },
        )
    return Ctx(bookRepo, service)
}

private fun audible(vararg titles: String): List<AudibleChapter> =
    titles.mapIndexed { i, t ->
        AudibleChapter(title = t, startMs = i * 1000L, durationMs = 1000L)
    }

private fun local(vararg titles: String): List<BookChapterPayload> =
    titles.mapIndexed { i, t ->
        BookChapterPayload(id = "ch-$i", title = t, duration = 1000L, startTime = i * 1000L)
    }

private fun bookWith(
    id: String,
    chapters: List<BookChapterPayload>,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "Untagged",
        sortTitle = null,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        hasScanWarning = false,
        totalDuration = 0L,
        cover = null,
        rootRelPath = "test/$id",
        inode = null,
        scannedAt = 0L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles = emptyList(),
        chapters = chapters,
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

private class ChapterFakeAudibleApi(
    private val chapters: List<AudibleChapter>,
) : AudibleApi {
    override suspend fun search(
        region: AudibleRegion,
        params: SearchParams,
    ): AppResult<List<AudibleSearchResult>> = AppResult.Success(emptyList())

    override suspend fun getBook(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleBook?> = AppResult.Success(null)

    override suspend fun getChapters(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<AudibleChapter>> = AppResult.Success(chapters)

    override suspend fun getProductTags(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<ProductTag>> = AppResult.Success(emptyList())
}

private class NoOpITunes : ITunesApi {
    override suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?> = AppResult.Success(null)

    override suspend fun searchCovers(
        title: String,
        author: String,
    ): AppResult<List<ITunesCoverHit>> = AppResult.Success(emptyList())
}
