package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.MetadataImageDeps
import com.calypsan.listenup.server.api.MetadataLookupServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.PermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleContributor
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.ProductTag
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.metadata.spi.MetadataCapability
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.CoverSearchService
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.testCoordinator
import com.calypsan.listenup.server.testing.testEnrichmentDeps
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.io.files.Path
import java.nio.file.Files

/** Project Hail Mary's Audible ASIN, the edition every #1542 lookup test matches. */
const val PHM_ASIN = "B08G9RZBTT"

private const val MAX_COVER_BYTES = 10L * 1024 * 1024

/** An Audible that knows exactly one book, by its ASIN, and finds nothing on search. */
internal class OneBookAudible(
    private val book: AudibleBook,
) : AudibleApi {
    override suspend fun search(
        region: AudibleRegion,
        params: SearchParams,
    ): AppResult<List<AudibleSearchResult>> = AppResult.Success(emptyList())

    override suspend fun getBook(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleBook?> = AppResult.Success(book.takeIf { it.asin == asin })

    override suspend fun getChapters(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<AudibleChapter>> = AppResult.Success(emptyList())

    override suspend fun getProductTags(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<ProductTag>> = AppResult.Success(emptyList())
}

/** An iTunes that never has a cover. */
internal class NoCoversITunes : ITunesApi {
    override suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?> = AppResult.Success(null)

    override suspend fun searchCovers(
        title: String,
        author: String,
    ): AppResult<List<ITunesCoverHit>> = AppResult.Success(emptyList())
}

/** Project Hail Mary as Audible tells it, with the gaps #1542 fills: no description, no series. */
internal fun audibleHailMary(): AudibleBook =
    AudibleBook(
        asin = PHM_ASIN,
        title = "Project Hail Mary",
        subtitle = "",
        authors = listOf(AudibleContributor(asin = "B00G0WYW92", name = "Andy Weir")),
        narrators = listOf(AudibleContributor(asin = "", name = "Ray Porter")),
        publisher = "Audible Studios",
        releaseDate = "2021-05-04",
        runtimeMinutes = 970,
        description = "",
        coverUrl = "",
        series = emptyList(),
        genres = listOf("Science Fiction"),
        language = "english",
        rating = 4.8f,
        ratingCount = 100_000,
    )

/** A freshly scanned book with nothing but a title: what a match fills. */
internal fun scannedBook(id: String): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "Project Hail Mary",
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
        chapters = emptyList(),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

/** A lookup service over real repositories, and what a test reads back through them. */
internal class LookupRig(
    val service: MetadataLookupServiceImpl,
    val books: BookRepository,
    private val moods: MoodRepository,
    private val bookMoods: BookMoodRepository,
) {
    /** The live moods on [bookId], by name. */
    suspend fun moodNames(bookId: String): List<String> =
        bookMoods.findAllForBook(bookId).mapNotNull { moods.findById(it.moodId)?.name }
}

/**
 * [MetadataLookupServiceImpl] over real repositories in [dbs], composing Audible ([audible]) with
 * [extraProviders] on the shipped default routes, called by [caller] (ROOT unless a test says otherwise).
 */
internal fun lookupRig(
    dbs: SqlTestDatabases,
    audible: AudibleApi,
    extraProviders: List<MetadataCapability>,
    caller: UserPrincipal = UserPrincipal(UserId("root"), SessionId("s"), UserRole.ROOT),
): LookupRig {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val contributors = ContributorRepository(dbs.sql, bus, registry)
    val series = SeriesRepository(dbs.sql, bus, registry)
    val genres = GenreRepository(dbs.sql, bus, registry)
    val books = BookRepository(dbs.sql, bus, registry, dbs.driver, contributors, series, genres)
    val metadataService =
        MetadataService(audible = audible, itunes = NoCoversITunes(), cache = MetadataCacheRepository(dbs.sql))
    val tempDir = Files.createTempDirectory("hc-lookup-").also { it.toFile().deleteOnExit() }
    val service =
        MetadataLookupServiceImpl(
            metadataService = metadataService,
            coordinator = testCoordinator(metadataService, extraProviders = extraProviders),
            coverSearchService =
                CoverSearchService(
                    readBook = { null },
                    registry = MetadataProviderRegistry(emptyList()),
                    probeDimensions = { null },
                ),
            bookRepository = books,
            contributorRepository = contributors,
            seriesRepository = series,
            imageDeps =
                MetadataImageDeps(
                    imageStorage = ImageStorage(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
                    coverImageStore =
                        CoverImageStore(
                            ImageStore(Path(tempDir.resolve("covers").toString()), MAX_COVER_BYTES),
                        ),
                    imageHome = Path(tempDir.toString()),
                ),
            enrichmentDeps = testEnrichmentDeps(dbs.sql, dbs.driver, bus, registry),
            permissionPolicy = PermissionPolicy(dbs.sql),
            bookAccessPolicy = BookAccessPolicy(dbs.sql, dbs.driver),
            sqlDb = dbs.sql,
            genreRepository = genres,
            principal = PrincipalProvider { caller },
        )
    // The read-back repositories register on a registry of their own: the enrichment deps above already
    // hold the moods domains on [registry], and a registry is 1:1 per domain.
    val readBack = SyncRegistry()
    return LookupRig(
        service,
        books,
        MoodRepository(dbs.sql, bus, readBack),
        BookMoodRepository(dbs.sql, bus, readBack, driver = dbs.driver),
    )
}
