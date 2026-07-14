@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleContributorProfile
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.ProductTag
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

private val NOW = Instant.parse("2026-06-05T12:00:00Z")
private const val ASIN = "B0CHAPTERS"

class ChapterNameApplierTest :
    FunSpec({

        test("count match: selected ordinals renamed, timestamps preserved, others untouched") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = deps(this, audibleChapters = audible("Prologue", "Chapter One", "Chapter Two"))
                runTest {
                    deps.bookRepo.upsert(
                        bookWithChapters("b1", local("Track 1", "Track 2", "Track 3")),
                        clientOpId = null,
                    )

                    val result = deps.applier.apply(BookId("b1"), ASIN, AudibleRegion.US, ordinals = setOf(0, 2))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    val after =
                        deps.bookRepo
                            .findById(BookId("b1"))
                            .shouldNotBeNull()
                            .chapters
                            .sortedBy { it.startTime }
                    after[0].title shouldBe "Prologue"
                    after[1].title shouldBe "Track 2"
                    after[2].title shouldBe "Chapter Two"
                    after.map { it.startTime } shouldBe listOf(0L, 1000L, 2000L)
                    after.map { it.duration } shouldBe listOf(1000L, 1000L, 1000L)
                }
            }
        }

        test("count mismatch: returns ChapterCountMismatch and writes nothing") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = deps(this, audibleChapters = audible("A", "B", "C", "D", "E"))
                runTest {
                    deps.bookRepo.upsert(bookWithChapters("b1", local("Track 1", "Track 2")), clientOpId = null)
                    val revBefore =
                        deps.bookRepo
                            .findById(BookId("b1"))
                            .shouldNotBeNull()
                            .revision

                    val result = deps.applier.apply(BookId("b1"), ASIN, AudibleRegion.US, ordinals = setOf(0, 1))

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<MetadataError.ChapterCountMismatch>()
                    val after = deps.bookRepo.findById(BookId("b1")).shouldNotBeNull()
                    after.chapters.map { it.title } shouldBe listOf("Track 1", "Track 2")
                    after.revision shouldBe revBefore
                }
            }
        }

        test("empty ordinals: success no-op, names unchanged") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = deps(this, audibleChapters = audible("Prologue", "Chapter One"))
                runTest {
                    deps.bookRepo.upsert(bookWithChapters("b1", local("Track 1", "Track 2")), clientOpId = null)
                    val revBefore =
                        deps.bookRepo
                            .findById(BookId("b1"))
                            .shouldNotBeNull()
                            .revision

                    val result = deps.applier.apply(BookId("b1"), ASIN, AudibleRegion.US, ordinals = emptySet())

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    val after = deps.bookRepo.findById(BookId("b1")).shouldNotBeNull()
                    after.chapters.map { it.title } shouldBe listOf("Track 1", "Track 2")
                    after.revision shouldBe revBefore
                }
            }
        }

        test("book absent: NotFound") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = deps(this, audibleChapters = audible("A"))
                runTest {
                    val result = deps.applier.apply(BookId("missing"), ASIN, AudibleRegion.US, ordinals = setOf(0))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<MetadataError.NotFound>()
                }
            }
        }

        test("Audible returns no chapters: NotFound") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = deps(this, audibleChapters = emptyList())
                runTest {
                    deps.bookRepo.upsert(bookWithChapters("b1", local("Track 1")), clientOpId = null)
                    val result = deps.applier.apply(BookId("b1"), ASIN, AudibleRegion.US, ordinals = setOf(0))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<MetadataError.NotFound>()
                }
            }
        }
    })

// ─── helpers ─────────────────────────────────────────────────────────────────

private data class Deps(
    val bookRepo: BookRepository,
    val applier: ChapterNameApplier,
)

private fun deps(
    db: SqlTestDatabases,
    audibleChapters: List<AudibleChapter>,
): Deps {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val contributorRepo = ContributorRepository(db.sql, bus, registry)
    val seriesRepo = SeriesRepository(db.sql, bus, registry)
    val bookRepo =
        BookRepository(
            db.sql,
            bus,
            registry,
            db.driver,
            contributorRepo,
            seriesRepo,
            GenreRepository(db.sql, bus, registry),
        )
    val metadataService =
        MetadataService(
            audible = ChapterFakeAudibleApi(audibleChapters),
            itunes = NoOpITunes(),
            cache = MetadataCacheRepository(db.sql, clock = FixedClock(NOW)),
        )
    return Deps(bookRepo, ChapterNameApplier(bookRepo, metadataService))
}

private fun audible(vararg titles: String): List<AudibleChapter> =
    titles.mapIndexed { i, t ->
        AudibleChapter(title = t, startMs = i * 1000L, durationMs = 1000L)
    }

private fun local(vararg titles: String): List<BookChapterPayload> =
    titles.mapIndexed { i, t ->
        BookChapterPayload(id = "ch-$i", title = t, duration = 1000L, startTime = i * 1000L)
    }

private fun bookWithChapters(
    id: String,
    chapters: List<BookChapterPayload>,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "Untagged Book",
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
    ) = AppResult.Success(emptyList<AudibleSearchResult>())

    override suspend fun getBook(
        region: AudibleRegion,
        asin: String,
    ) = AppResult.Success<AudibleBook?>(null)

    override suspend fun getChapters(
        region: AudibleRegion,
        asin: String,
    ) = AppResult.Success(chapters)

    override suspend fun getContributor(
        region: AudibleRegion,
        asin: String,
    ) = AppResult.Success<AudibleContributorProfile?>(null)

    override suspend fun searchContributors(
        region: AudibleRegion,
        name: String,
    ) = AppResult.Success(emptyList<AudibleContributorProfile>())

    override suspend fun getProductTags(
        region: AudibleRegion,
        asin: String,
    ) = AppResult.Success(emptyList<ProductTag>())
}

private class NoOpITunes : ITunesApi {
    override suspend fun findCover(
        title: String,
        author: String,
    ) = AppResult.Success<ITunesCoverHit?>(null)

    override suspend fun searchCovers(
        title: String,
        author: String,
    ) = AppResult.Success<List<ITunesCoverHit>>(emptyList())
}
