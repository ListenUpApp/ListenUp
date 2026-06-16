@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleContributor
import com.calypsan.listenup.server.metadata.audible.AudibleContributorProfile
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.ProductTag
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.metadata.provider.AudibleMetadataProvider
import com.calypsan.listenup.server.services.BookMoodWriter
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.BookTagWriter
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreAutoCreator
import com.calypsan.listenup.server.services.GenreHierarchyFromLadder
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

private val ENRICH_NOW = Instant.parse("2026-05-24T12:00:00Z")
private const val ENRICH_ASIN = "B0ENRICH01"
private const val BOOK_ID = "enrich-book"
private const val MAX_COVER_BYTES = 10L * 1024 * 1024

private val TINY_JPEG =
    byteArrayOf(
        0xFF.toByte(),
        0xD8.toByte(),
        0xFF.toByte(),
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
    )

// Base selection with no moods/tags chosen — used as the starting point each test copies from.
private val ENRICH_SELECTION =
    MetadataApplySelection(
        title = true,
        subtitle = true,
        description = true,
        publisher = true,
        releaseDate = true,
        language = true,
        cover = false,
        authorAsins = emptySet(),
        narratorAsins = emptySet(),
        seriesAsins = emptySet(),
    )

/**
 * Verifies the user-selected Audible mood/trope enrichment wired into the metadata-apply path.
 *
 * The applier no longer re-scrapes Audible at apply time: moods + tropes are scraped and
 * classified at lookup time (see `MetadataLookupServiceImpl.enrichWithMoodsAndTags`) and
 * presented to the user as toggleable chips. The apply path honors that selection —
 * [MetadataApplySelection.moods] / [MetadataApplySelection.tags] — writing the chosen values
 * additively. This test covers:
 *
 *  - **Happy path:** a selection carrying moods + tags persists them to `book_moods` /
 *    `book_tags`; a value the user deselected is never written.
 *  - **Empty selection:** a selection with no moods/tags writes nothing, but the match still
 *    succeeds (the text metadata commits regardless).
 *  - **Additive re-match (#573):** re-applying with a second selection accumulates the
 *    moods/tropes (the writers are add-only). Selective apply is the future #573 fix.
 *
 * Drives [BookMetadataApplier] directly (rather than through [MetadataLookupServiceImpl]) so the
 * same mood/tag repositories used for assertion are injected without round-tripping the DI module.
 * The `productTagSource` is no longer consulted by the apply path; the stub asserts that.
 */
class MetadataApplyEnrichmentTest :
    FunSpec({

        test("apply persists the user-selected moods + tags") {
            withInMemoryDatabase {
                val ctx = enrichmentCtx(this)
                // The apply path must NOT scrape — a throwing source proves moods/tags come from the selection.
                val applier = ctx.applier { _, _ -> error("apply must not scrape product tags") }
                val selection =
                    ENRICH_SELECTION.copy(
                        moods = setOf("Feel-Good", "Tense"),
                        tags = setOf("Found Family"),
                    )

                runTest {
                    ctx.bookRepo.upsert(minimalEnrichBook(BOOK_ID), clientOpId = null)

                    val result = applier.apply(BookId(BOOK_ID), ENRICH_ASIN, AudibleRegion.US, selection)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    ctx.moodNamesForBook(BOOK_ID) shouldContainExactlyInAnyOrder listOf("Feel-Good", "Tense")
                    ctx.tagNamesForBook(BOOK_ID) shouldContainExactlyInAnyOrder listOf("Found Family")
                }
            }
        }

        test("an empty mood/tag selection writes nothing but still succeeds") {
            withInMemoryDatabase {
                val ctx = enrichmentCtx(this)
                val applier = ctx.applier { _, _ -> error("apply must not scrape product tags") }

                runTest {
                    ctx.bookRepo.upsert(minimalEnrichBook(BOOK_ID), clientOpId = null)

                    val result = applier.apply(BookId(BOOK_ID), ENRICH_ASIN, AudibleRegion.US, ENRICH_SELECTION)

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    ctx.bookRepo.findById(BookId(BOOK_ID))?.description shouldBe "An enriched description."

                    ctx.moodNamesForBook(BOOK_ID) shouldHaveSize 0
                    ctx.tagNamesForBook(BOOK_ID) shouldHaveSize 0
                }
            }
        }

        // #573: re-matching ACCUMULATES moods/tropes because the writers are add-only.
        // A future selective-apply surface (#573) is the fix; for now accumulation is expected.
        test("re-match accumulates moods + tropes (additive, #573)") {
            withInMemoryDatabase {
                val ctx = enrichmentCtx(this)
                val applier = ctx.applier { _, _ -> error("apply must not scrape product tags") }

                runTest {
                    ctx.bookRepo.upsert(minimalEnrichBook(BOOK_ID), clientOpId = null)

                    applier
                        .apply(
                            BookId(BOOK_ID),
                            ENRICH_ASIN,
                            AudibleRegion.US,
                            ENRICH_SELECTION.copy(moods = setOf("Tense"), tags = setOf("Heist")),
                        ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    applier
                        .apply(
                            BookId(BOOK_ID),
                            ENRICH_ASIN,
                            AudibleRegion.US,
                            ENRICH_SELECTION.copy(moods = setOf("Hopeful"), tags = setOf("Revenge")),
                        ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    ctx.moodNamesForBook(BOOK_ID) shouldContainExactlyInAnyOrder listOf("Tense", "Hopeful")
                    ctx.tagNamesForBook(BOOK_ID) shouldContainExactlyInAnyOrder listOf("Heist", "Revenge")
                }
            }
        }
    })

private class EnrichmentCtx(
    val db: Database,
    val bookRepo: BookRepository,
    val genreRepo: GenreRepository,
    val contributorRepo: ContributorRepository,
    val seriesRepo: SeriesRepository,
    val metadataService: MetadataService,
    val moodRepo: MoodRepository,
    val bookMoodRepo: BookMoodRepository,
    val tagRepo: TagRepository,
    val bookTagRepo: BookTagRepository,
    val tempDir: String,
) {
    fun applier(productTagSource: suspend (AudibleRegion, String) -> List<ProductTag>): BookMetadataApplier =
        BookMetadataApplier(
            bookRepository = bookRepo,
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            imageStorage = ImageStorage(HttpClient(MockEngine { _ -> respond(TINY_JPEG, HttpStatusCode.OK) })),
            coverImageStore = CoverImageStore(ImageStore(Path.of(tempDir).resolve("covers"), MAX_COVER_BYTES)),
            metadataProvider = AudibleMetadataProvider(metadataService),
            genreHierarchy = GenreHierarchyFromLadder(db, genreRepo, GenreAutoCreator(genreRepo)),
            db = db,
            ladderSource = { _, _ -> emptyList() },
            enrichmentDeps =
                MetadataEnrichmentDeps(
                    bookMoodWriter = BookMoodWriter(FixedClock(ENRICH_NOW), moodRepo, bookMoodRepo),
                    bookTagWriter = BookTagWriter(FixedClock(ENRICH_NOW), tagRepo, bookTagRepo),
                    productTagSource = productTagSource,
                ),
        )

    suspend fun moodNamesForBook(bookId: String): List<String> =
        bookMoodRepo
            .findAllForBook(bookId)
            .mapNotNull { moodRepo.findById(it.moodId)?.name }

    suspend fun tagNamesForBook(bookId: String): List<String> =
        bookTagRepo
            .findAllForBook(bookId)
            .mapNotNull { tagRepo.findById(it.tagId)?.name }
}

private fun enrichmentCtx(db: Database): EnrichmentCtx {
    db.seedTestLibraryAndFolder()
    val tempDir = Files.createTempDirectory("enrich-").also { it.toFile().deleteOnExit() }.toString()
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val contributorRepo = ContributorRepository(db, bus, registry)
    val seriesRepo = SeriesRepository(db, bus, registry)
    val genreRepo = GenreRepository(db, bus, registry)
    val bookRepo = BookRepository(db, bus, registry, contributorRepo, seriesRepo, genreRepo)
    val moodRepo = MoodRepository(db, bus, registry)
    val bookMoodRepo = BookMoodRepository(db, bus, registry)
    val tagRepo = TagRepository(db, bus, registry)
    val bookTagRepo = BookTagRepository(db, bus, registry)
    val metadataService =
        MetadataService(
            audible = EnrichStubAudibleApi(enrichBook()),
            itunes = NoOpEnrichITunesApi(),
            cache = MetadataCacheRepository(db, clock = FixedClock(ENRICH_NOW)),
        )
    return EnrichmentCtx(
        db = db,
        bookRepo = bookRepo,
        genreRepo = genreRepo,
        contributorRepo = contributorRepo,
        seriesRepo = seriesRepo,
        metadataService = metadataService,
        moodRepo = moodRepo,
        bookMoodRepo = bookMoodRepo,
        tagRepo = tagRepo,
        bookTagRepo = bookTagRepo,
        tempDir = tempDir,
    )
}

private fun enrichBook(): AudibleBook =
    AudibleBook(
        asin = ENRICH_ASIN,
        title = "An Enriched Tale",
        subtitle = "",
        authors = listOf(AudibleContributor(asin = "AENRICH", name = "Jane Doe")),
        narrators = emptyList(),
        publisher = "ListenUp Audio",
        releaseDate = "2026-01-01",
        runtimeMinutes = 600,
        description = "An enriched description.",
        coverUrl = "https://example.com/enrich.jpg",
        series = emptyList(),
        genres = listOf("Fantasy"),
        language = "english",
        rating = 4.5f,
        ratingCount = 100,
    )

private fun minimalEnrichBook(id: String): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "An Enriched Tale (untagged)",
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
        rootRelPath = "test/enrich",
        inode = null,
        scannedAt = 0L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles = emptyList(),
        chapters = emptyList(),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

/** Fake [AudibleApi] returning a single canned book; all other surfaces are empty. */
private class EnrichStubAudibleApi(
    private val book: AudibleBook?,
) : AudibleApi {
    override suspend fun search(
        region: AudibleRegion,
        params: SearchParams,
    ): AppResult<List<AudibleSearchResult>> = AppResult.Success(emptyList())

    override suspend fun getBook(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleBook?> = AppResult.Success(book)

    override suspend fun getChapters(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<AudibleChapter>> = AppResult.Success(emptyList())

    override suspend fun getContributor(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleContributorProfile?> = AppResult.Success(null)

    override suspend fun searchContributors(
        region: AudibleRegion,
        name: String,
    ): AppResult<List<AudibleContributorProfile>> = AppResult.Success(emptyList())

    override suspend fun getProductTags(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<ProductTag>> = AppResult.Success(emptyList())
}

/** Stub [ITunesApi] that never finds a cover. */
private class NoOpEnrichITunesApi : ITunesApi {
    override suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?> = AppResult.Success(null)

    override suspend fun searchCovers(
        title: String,
        author: String,
    ): AppResult<List<ITunesCoverHit>> = AppResult.Success(emptyList())
}
