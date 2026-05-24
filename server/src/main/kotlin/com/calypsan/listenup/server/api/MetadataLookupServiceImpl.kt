package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataChapter
import com.calypsan.listenup.api.dto.MetadataChapters
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.dto.MetadataContributorRef
import com.calypsan.listenup.api.dto.MetadataSearchResults
import com.calypsan.listenup.api.dto.MetadataSeriesRef
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleContributorProfile
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.AudibleSeriesEntry
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.metadata.ImageStorage
import kotlinx.io.files.Path

/**
 * Server-side implementation of [MetadataLookupService].
 *
 * Delegates read-only operations (search / fetch / refresh) to [MetadataService],
 * which wraps the Audible and iTunes adapters with TTL caching. The two apply
 * methods ([applyBookMetadata], [applyContributorMetadata]) write through the
 * syncable substrate via [BookRepository] and [ContributorRepository].
 *
 * Internal Audible / iTunes types are projected to wire DTOs at this boundary —
 * [AudibleBook] → [MetadataBook], etc. No raw Audible types cross the RPC wire.
 */
internal class MetadataLookupServiceImpl(
    private val metadataService: MetadataService,
    private val bookRepository: BookRepository,
    private val contributorRepository: ContributorRepository,
    private val seriesRepository: SeriesRepository,
    private val imageStorage: ImageStorage,
    private val libraryPath: Path,
) : MetadataLookupService {
    override suspend fun searchBooks(
        query: String,
        region: AudibleRegion?,
    ): AppResult<MetadataSearchResults> {
        val params = SearchParams(keywords = query)
        val result =
            if (region == null) {
                metadataService.searchWithFallback(params)
            } else {
                metadataService.search(region, params)
            }
        return result.map { hits -> MetadataSearchResults(hits = hits.map { it.toMetadataBook() }) }
    }

    override suspend fun getBookMetadata(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataBook?> = metadataService.getBook(region, asin).map { it?.toMetadataBook() }

    override suspend fun getBookChapters(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataChapters?> =
        metadataService.getBookChapters(region, asin).map { chapters ->
            if (chapters.isEmpty()) null else MetadataChapters(chapters = chapters.map { it.toMetadataChapter() })
        }

    // Stub: Audible has no official contributor-search API. The Go reference uses
    // HTML scraping of /search?searchAuthor=, which requires complex JS-rendered
    // page parsing beyond the lightweight regex approach used for contributor
    // profiles. A later phase will land a proper implementation.
    // The method signature is included in the contract so client UI can be built
    // against it now.
    override suspend fun searchContributorMetadata(query: String): AppResult<List<MetadataContributorHit>> =
        AppResult.Success(emptyList())

    override suspend fun getContributorMetadata(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataContributorProfile?> =
        metadataService.getContributor(region, asin).map { it?.toMetadataContributorProfile() }

    override suspend fun refreshBookMetadata(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataBook?> = metadataService.getBook(region, asin, refresh = true).map { it?.toMetadataBook() }

    override suspend fun applyBookMetadata(
        bookId: BookId,
        asin: String,
        region: AudibleRegion,
    ): AppResult<Unit> =
        BookMetadataApplier(
            bookRepository = bookRepository,
            contributorRepository = contributorRepository,
            seriesRepository = seriesRepository,
            imageStorage = imageStorage,
            metadataService = metadataService,
            libraryPath = libraryPath,
        ).apply(bookId, asin, region)

    override suspend fun applyContributorMetadata(
        contributorId: ContributorId,
        asin: String,
        region: AudibleRegion,
    ): AppResult<Unit> =
        ContributorMetadataApplier(
            contributorRepository = contributorRepository,
            imageStorage = imageStorage,
            metadataService = metadataService,
            libraryPath = libraryPath,
        ).apply(contributorId, asin, region)
}

// ─── Internal → wire DTO mappers ─────────────────────────────────────────────

private fun AudibleSearchResult.toMetadataBook(): MetadataBook =
    MetadataBook(
        asin = asin,
        title = title,
        subtitle = subtitle.takeIf { it.isNotBlank() },
        description = null,
        publisher = null,
        releaseDate = releaseDate.takeIf { it.isNotBlank() },
        runtimeMinutes = runtimeMinutes.takeIf { it > 0 },
        language = null,
        authors = authors.map { MetadataContributorRef(asin = it.asin.takeIf { a -> a.isNotBlank() }, name = it.name) },
        narrators =
            narrators.map {
                MetadataContributorRef(
                    asin =
                        it.asin.takeIf { a ->
                            a.isNotBlank()
                        },
                    name = it.name,
                )
            },
        series = emptyList(),
        coverUrl = coverUrl.takeIf { it.isNotBlank() },
        coverUrlMaxSize = null,
    )

private fun AudibleBook.toMetadataBook(): MetadataBook =
    MetadataBook(
        asin = asin,
        title = title,
        subtitle = subtitle.takeIf { it.isNotBlank() },
        description = description.takeIf { it.isNotBlank() },
        publisher = publisher.takeIf { it.isNotBlank() },
        releaseDate = releaseDate.takeIf { it.isNotBlank() },
        runtimeMinutes = runtimeMinutes.takeIf { it > 0 },
        language = language.takeIf { it.isNotBlank() },
        authors = authors.map { MetadataContributorRef(asin = it.asin.takeIf { a -> a.isNotBlank() }, name = it.name) },
        narrators =
            narrators.map {
                MetadataContributorRef(
                    asin =
                        it.asin.takeIf { a ->
                            a.isNotBlank()
                        },
                    name = it.name,
                )
            },
        series = series.map { it.toMetadataSeriesRef() },
        coverUrl = coverUrl.takeIf { it.isNotBlank() },
        coverUrlMaxSize = null,
    )

private fun AudibleSeriesEntry.toMetadataSeriesRef(): MetadataSeriesRef =
    MetadataSeriesRef(
        asin = asin.takeIf { it.isNotBlank() },
        title = name,
        sequence = position.takeIf { it.isNotBlank() },
    )

private fun AudibleChapter.toMetadataChapter(): MetadataChapter =
    MetadataChapter(title = title, startMs = startMs, lengthMs = durationMs)

private fun AudibleContributorProfile.toMetadataContributorProfile(): MetadataContributorProfile =
    MetadataContributorProfile(
        asin = asin,
        name = name,
        sortName = null,
        description = biography.takeIf { it.isNotBlank() },
        imageUrl = imageUrl.takeIf { it.isNotBlank() },
        birthDate = null,
        deathDate = null,
        website = null,
    )
