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
import com.calypsan.listenup.server.metadata.spi.BookFindSource
import com.calypsan.listenup.server.metadata.spi.ExternalRatingMeta
import com.calypsan.listenup.server.metadata.spi.FindAnswer
import com.calypsan.listenup.server.metadata.spi.FindLookup
import com.calypsan.listenup.server.metadata.spi.FindStep
import com.calypsan.listenup.server.metadata.spi.FoundBook
import com.calypsan.listenup.server.metadata.spi.GenreLadderSource
import com.calypsan.listenup.server.metadata.spi.GenreMeta
import com.calypsan.listenup.server.metadata.spi.GenreSource
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.RatingSource
import com.calypsan.listenup.server.metadata.spi.RegionalSource
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import com.calypsan.listenup.server.metadata.spi.SeriesSource
import com.calypsan.listenup.server.services.MetadataService

/**
 * The Audible catalog re-skinned onto the metadata capability SPI.
 *
 * A single object implementing every capability Audible's catalog supports —
 * [BookIdentitySource] (search), [BookCoreSource] (book + credits), [ChapterSource],
 * [CoverSource], [SeriesSource], [GenreSource], and [RatingSource] — and, for Match details, a
 * [BookFindSource] with stores ([RegionalSource]). It deliberately
 * does *not* implement `ContributorSource`: Audible's contributor-profile scrape is
 * dead, and that capability moves to Audnexus in a later step.
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
 * [getRating] is the one ASIN-keyed lookup that doesn't stop at a single storefront: some ASINs
 * are region-locked (an Audible Canada title has no listing on .com, .co.uk or .com.au), so it
 * walks stores via [MetadataService.getBookInAnyRegion] starting from the resolved region — see
 * that method's KDoc for the store order and the stub-vs-failure distinction. `getBookCore` and
 * the other ASIN-keyed lookups above are unchanged and still query a single region.
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
    GenreSource,
    GenreLadderSource,
    RatingSource,
    BookFindSource,
    RegionalSource {
    override val id: MetadataProviderId = MetadataProviderId.AUDIBLE
    override val ratingSource: ExternalRatingSource = ExternalRatingSource.AUDIBLE

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
        refresh: Boolean,
    ): AppResult<ChapterListMeta?> {
        val asin = book.asin ?: return AppResult.Success(null)
        return metadataService.getBookChapters(regionFor(locale), asin, refresh).map { it.toChapterListMeta() }
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

    override suspend fun getGenreLadders(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<List<String>>?> {
        val asin = book.asin ?: return AppResult.Success(null)
        return metadataService.getBook(regionFor(locale), asin).map { it?.genreLadders }
    }

    override suspend fun getRating(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ExternalRatingMeta?> {
        val asin = book.asin ?: return AppResult.Success(null)
        return metadataService
            .getBookInAnyRegion(asin, preferred = regionFor(locale), refresh = refresh)
            .map { regional -> regional?.let { it.book.toExternalRatingMeta(it.region) } }
    }

    override fun hasStore(region: String): Boolean = AudibleRegion.fromCodeOrNull(region) != null

    /**
     * Find, in one request per key plus one search. Each link is read in the store it was found in (else the
     * store asked for), the book's ASIN only when there is no link, and the search in the store asked for —
     * Find never falls back to another store, so an empty answer reads as "not found in this store". Any
     * failure fails the whole answer, so a retry asks again.
     */
    override suspend fun findBooks(
        lookup: FindLookup,
        locale: MetadataLocale,
    ): AppResult<FindAnswer> {
        val region = regionFor(locale)
        val steps = mutableSetOf<FindStep>()
        val books = mutableListOf<FoundBook>()
        for (key in lookup.keys) {
            steps += FindStep.LINK
            val keyRegion = key.region?.let { AudibleRegion.fromCodeOrNull(it) } ?: region
            when (val found = foundByAsin(key.id, keyRegion, viaLink = true)) {
                is AppResult.Failure -> return found
                is AppResult.Success -> found.data?.let { books += it }
            }
        }
        val asin = lookup.asin
        if (lookup.keys.isEmpty() && asin != null) {
            steps += FindStep.ASIN
            when (val found = foundByAsin(asin, region, viaLink = false)) {
                is AppResult.Failure -> return found
                is AppResult.Success -> found.data?.let { books += it }
            }
        }
        steps += FindStep.TEXT
        when (val hits = metadataService.search(region, SearchParams(keywords = lookup.text))) {
            is AppResult.Failure -> return hits
            is AppResult.Success -> books += hits.data.map { it.toFoundBook(region) }
        }
        return AppResult.Success(FindAnswer(books, steps))
    }

    /** The book at [asin] in [region]'s store, with its chapter count; null when that store hasn't it. */
    private suspend fun foundByAsin(
        asin: String,
        region: AudibleRegion,
        viaLink: Boolean,
    ): AppResult<FoundBook?> {
        val book =
            when (val read = metadataService.getBook(region, asin)) {
                is AppResult.Failure -> return read
                is AppResult.Success -> read.data ?: return AppResult.Success(null)
            }
        return AppResult.Success(book.toFoundBook(region, viaLink, chapterCountOf(asin, region)))
    }

    /**
     * How many chapters Audible lists for [asin], or null when it lists none or can't say. The count is only a
     * ranking hint ("36 chapters"), so failing to read it leaves it unknown rather than failing the Find; it is
     * cached for 30 days, so a retry doesn't ask again.
     */
    private suspend fun chapterCountOf(
        asin: String,
        region: AudibleRegion,
    ): Int? =
        when (val chapters = metadataService.getBookChapters(region, asin)) {
            is AppResult.Success -> chapters.data.size.takeIf { it > 0 }
            is AppResult.Failure -> null
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
