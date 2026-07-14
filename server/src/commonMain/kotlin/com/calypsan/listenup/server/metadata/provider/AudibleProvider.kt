package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreSource
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.BookIdentitySource
import com.calypsan.listenup.server.metadata.spi.BookMatch
import com.calypsan.listenup.server.metadata.spi.ChapterListMeta
import com.calypsan.listenup.server.metadata.spi.ChapterSource
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.CoverSource
import com.calypsan.listenup.server.metadata.spi.GenreMeta
import com.calypsan.listenup.server.metadata.spi.GenreSource
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import com.calypsan.listenup.server.metadata.spi.SeriesSource
import com.calypsan.listenup.server.services.MetadataService

/**
 * The Audible catalog re-skinned onto the metadata capability SPI.
 *
 * A single object implementing every capability Audible's catalog supports —
 * [BookIdentitySource] (search), [BookCoreSource] (book + credits), [ChapterSource],
 * [CoverSource], [SeriesSource], and [GenreSource]. It deliberately does *not*
 * implement `ContributorSource`: Audible's contributor-profile scrape is dead, and
 * that capability moves to Audnexus in a later step.
 *
 * Orchestration only — every method is a thin `.map { it.toX() }` over
 * [MetadataService] (which owns TTL caching and region-aware fallback); the actual
 * Audible → neutral-meta mapping lives in the pure functions in `AudibleSpiMappers`.
 *
 * ### Locale → region
 * [MetadataLocale] is mapped to an [AudibleRegion] via [AudibleRegion.fromCodeOrNull].
 * A recognized code queries that storefront directly; an unrecognized one falls back
 * to [MetadataService.searchWithFallback] (default region, then US) for search-keyed
 * lookups and to [defaultRegion] for ASIN-keyed lookups — the never-strand rule at the
 * provider edge. Proper locale plumbing lands with the region migration in a later step.
 *
 * Server-internal: provider ids never cross the RPC wire.
 */
internal class AudibleProvider(
    private val metadataService: MetadataService,
    private val defaultRegion: AudibleRegion = AudibleRegion.US,
) : BookIdentitySource,
    BookCoreSource,
    ChapterSource,
    CoverSource,
    SeriesSource,
    GenreSource {
    override val id: MetadataProviderId = MetadataProviderId.AUDIBLE

    override suspend fun searchBooks(
        query: String,
        locale: MetadataLocale,
    ): AppResult<List<BookMatch>> = searchAudible(query, locale).map { hits -> hits.map { it.toBookMatch() } }

    override suspend fun getBookCore(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<BookCoreMeta?> {
        val asin = book.asin ?: return AppResult.Success(null)
        return metadataService
            .getBook(regionFor(locale), asin, refresh = refresh)
            .map { it?.toBookCoreMeta() }
    }

    override suspend fun getChapters(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<ChapterListMeta?> {
        val asin = book.asin ?: return AppResult.Success(null)
        return metadataService.getBookChapters(regionFor(locale), asin).map { it.toChapterListMeta() }
    }

    override suspend fun searchCovers(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<CoverMeta>> = searchAudible(book.searchQuery(), locale).map { it.toCoverMetas() }

    override suspend fun getSeries(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<SeriesMeta>?> {
        val asin = book.asin ?: return AppResult.Success(null)
        return metadataService.getBook(regionFor(locale), asin).map { it?.series?.map { s -> s.toSeriesMeta() } }
    }

    override suspend fun getGenres(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<GenreMeta>?> {
        val asin = book.asin ?: return AppResult.Success(null)
        return metadataService.getBook(regionFor(locale), asin).map { it?.genres?.toGenreMetas() }
    }

    /**
     * Search-keyed lookup shared by [searchBooks] and [searchCovers]. A recognized [locale]
     * queries that region directly; an unrecognized one uses the default-then-US fallback.
     */
    private suspend fun searchAudible(
        query: String,
        locale: MetadataLocale,
    ) = SearchParams(keywords = query).let { params ->
        when (val region = AudibleRegion.fromCodeOrNull(locale.region)) {
            null -> metadataService.searchWithFallback(params)
            else -> metadataService.search(region, params)
        }
    }

    /** Resolves an ASIN-keyed lookup's region, falling back to [defaultRegion] for unknown locales. */
    private fun regionFor(locale: MetadataLocale): AudibleRegion =
        AudibleRegion.fromCodeOrNull(locale.region) ?: defaultRegion
}

/** The free-text query Audible search runs for a cover lookup: title plus known author. */
private fun BookIdentity.searchQuery(): String = "$title ${primaryAuthor.orEmpty()}".trim()
