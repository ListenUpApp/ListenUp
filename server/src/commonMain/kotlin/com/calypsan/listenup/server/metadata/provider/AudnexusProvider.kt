@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.server.metadata.audnexus.AudnexusApi
import com.calypsan.listenup.server.metadata.audnexus.AudnexusAuthor
import com.calypsan.listenup.server.metadata.audnexus.AudnexusAuthorProfile
import com.calypsan.listenup.server.metadata.audnexus.AudnexusBook
import com.calypsan.listenup.server.metadata.audnexus.AudnexusChapters
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreSource
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.ChapterListMeta
import com.calypsan.listenup.server.metadata.spi.ChapterSource
import com.calypsan.listenup.server.metadata.spi.ContributorHitMeta
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import com.calypsan.listenup.server.metadata.spi.PersonAnswer
import com.calypsan.listenup.server.metadata.spi.PersonFindSource
import com.calypsan.listenup.server.metadata.spi.PersonLookup
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.CoverSource
import com.calypsan.listenup.server.metadata.spi.GenreMeta
import com.calypsan.listenup.server.metadata.spi.GenreSource
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import com.calypsan.listenup.server.metadata.spi.SeriesSource
import com.calypsan.listenup.server.services.MetadataCacheRepository
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * The Audnexus aggregator re-skinned onto the metadata capability SPI — the source
 * that turns contributor matching back on.
 *
 * A single object implementing every capability Audnexus's catalog supports:
 * [BookCoreSource] (book + credits), [ContributorSource] (author search + profile),
 * [ChapterSource] (catalog-verified chapters), [CoverSource] (the book's cover image),
 * [SeriesSource], and [GenreSource]. It has no book *search* endpoint, so it is not a
 * `BookIdentitySource` — Audnexus is consulted once a book's ASIN is known.
 *
 * ### Caching
 * Audnexus is a free, community-run service; every ASIN-keyed fetch is TTL-cached
 * through [cache] so the coordinator's parallel core/genre/series/cover fan-out for
 * one book resolves to a single `/books/{asin}` round-trip (and repeats across
 * previews hit the cache), matching the cached parity `AudibleProvider` gets from
 * `MetadataService`. Cache keys are provider-scoped ([MetadataProviderId.AUDNEXUS]),
 * so they never collide with Audible's entries.
 *
 * ### Locale → region
 * [MetadataLocale.region] is Audnexus's region vocabulary directly (same lowercase
 * codes), so it is passed straight through — no per-provider region enum.
 *
 * Orchestration + caching only; the Audnexus → neutral-meta mapping lives in the pure
 * functions in `AudnexusSpiMappers`. Server-internal: provider ids never cross the wire.
 */
internal class AudnexusProvider(
    private val client: AudnexusApi,
    private val cache: MetadataCacheRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clock: Clock = Clock.System,
) : BookCoreSource,
    PersonFindSource,
    ChapterSource,
    CoverSource,
    SeriesSource,
    GenreSource {
    override val id: MetadataProviderId = MetadataProviderId.AUDNEXUS

    /**
     * Audible's author pages in a people Find (matching redesign PR 4): the person's own ASIN, the author ASINs
     * your books' Audible credits name (whatever the person did on those books here), and a name search — each answer cached as the profile and book reads already
     * are. Photos come from the profiles of at most five people. A book's credits are read from the cache when it
     * holds them, so only uncached books cost an Audnexus call.
     */
    override suspend fun findPeople(
        lookup: PersonLookup,
        locale: MetadataLocale,
    ): AppResult<PersonAnswer> =
        AudnexusPeople(
            search = { name -> searchContributors(name, locale) },
            book = { asin -> fetchBook(asin, locale.region, refresh = false) },
            cachedBook = { asin -> cachedBook(asin, locale.region) },
            profile = { key -> getContributor(key, locale) },
        ).find(lookup)

    override suspend fun getBookCore(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<BookCoreMeta?> {
        val asin = book.asin ?: return AppResult.Success(null)
        return fetchBook(asin, locale.region, refresh).map { it?.toBookCoreMeta() }
    }

    override suspend fun getGenres(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<GenreMeta>?> {
        val asin = book.asin ?: return AppResult.Success(null)
        return fetchBook(asin, locale.region, refresh = false).map { it?.run { genres.toGenreMetas() } }
    }

    override suspend fun getSeries(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<SeriesMeta>?> {
        val asin = book.asin ?: return AppResult.Success(null)
        return fetchBook(asin, locale.region, refresh = false).map { it?.toSeriesMetas() }
    }

    override suspend fun searchCovers(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<CoverMeta>> {
        val asin = book.asin ?: return AppResult.Success(emptyList())
        return fetchBook(asin, locale.region, refresh = false).map { it?.toCoverMetas().orEmpty() }
    }

    override suspend fun getChapters(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ChapterListMeta?> {
        val asin = book.asin ?: return AppResult.Success(null)
        return cachedNullable(
            cacheKey = "chapters:$asin",
            region = locale.region,
            ttl = CHAPTER_TTL,
            refresh = refresh,
            serializer = AudnexusChapters.serializer(),
        ) {
            client.getChapters(asin, locale.region)
        }.map { it?.toChapterListMeta() }
    }

    override suspend fun searchContributors(
        name: String,
        locale: MetadataLocale,
    ): AppResult<List<ContributorHitMeta>> =
        cached(
            cacheKey = "author-search:${name.trim().lowercase()}",
            region = locale.region,
            ttl = SEARCH_TTL,
            serializer = ListSerializer(AudnexusAuthor.serializer()),
        ) { client.searchAuthors(name, locale.region) }
            // Audnexus indexes an author once per catalogued region/edition, so a common name
            // returns the same ASIN several times over. One hit per author is the contract our
            // SPI promises — dedupe by ASIN (not by name: distinct people do share names).
            // Applied after the cache read, so entries cached before this fix stay valid.
            .map { hits -> hits.mapNotNull { it.toContributorHitMetaOrNull() }.distinctBy(ContributorHitMeta::key) }

    override suspend fun getContributor(
        key: String,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ContributorMeta?> =
        cachedNullable(
            cacheKey = "author:$key",
            region = locale.region,
            ttl = AUTHOR_TTL,
            refresh = refresh,
            serializer = AudnexusAuthorProfile.serializer(),
        ) {
            client.getAuthor(key, locale.region)
        }.map { it?.toContributorMeta() }

    /** The `/books/{asin}` fetch shared by core, genres, series, and cover — cached once per ASIN+region. */
    private suspend fun fetchBook(
        asin: String,
        region: String,
        refresh: Boolean,
    ): AppResult<AudnexusBook?> =
        cachedNullable(
            cacheKey = bookCacheKey(asin),
            region = region,
            ttl = BOOK_TTL,
            refresh = refresh,
            serializer = AudnexusBook.serializer(),
        ) { client.getBook(asin, region) }

    /** The cached `/books/{asin}` answer, never a network call: `null` when the cache holds none (or a stale shape). */
    private suspend fun cachedBook(
        asin: String,
        region: String,
    ): AppResult<AudnexusBook?>? {
        val cachedJson = cache.get(id, region, bookCacheKey(asin)) ?: return null
        if (cachedJson == NULL_SENTINEL) return AppResult.Success(null)
        return try {
            AppResult.Success(json.decodeFromString(AudnexusBook.serializer(), cachedJson))
        } catch (_: SerializationException) {
            null
        }
    }

    // ── Caching helpers (mirror MetadataService, provider-scoped to AUDNEXUS) ──

    /** Cache-through for a non-nullable value; a stale-schema entry is treated as a miss and re-fetched. */
    private suspend fun <T> cached(
        cacheKey: String,
        region: String,
        ttl: Duration,
        serializer: KSerializer<T>,
        fetch: suspend () -> AppResult<T>,
    ): AppResult<T> {
        cache.get(id, region, cacheKey)?.let { cachedJson ->
            return try {
                AppResult.Success(json.decodeFromString(serializer, cachedJson))
            } catch (_: SerializationException) {
                fetchAndStore(cacheKey = cacheKey, region = region, ttl = ttl, serializer = serializer, fetch = fetch)
            }
        }
        return fetchAndStore(cacheKey = cacheKey, region = region, ttl = ttl, serializer = serializer, fetch = fetch)
    }

    /** Cache-through for a nullable value; a `null` result is stored as the sentinel `"null"`. */
    private suspend fun <T> cachedNullable(
        cacheKey: String,
        region: String,
        ttl: Duration,
        refresh: Boolean,
        serializer: KSerializer<T>,
        fetch: suspend () -> AppResult<T?>,
    ): AppResult<T?> {
        if (!refresh) {
            cache.get(id, region, cacheKey)?.let { cachedJson ->
                return try {
                    if (cachedJson == NULL_SENTINEL) {
                        AppResult.Success(null)
                    } else {
                        AppResult.Success(json.decodeFromString(serializer, cachedJson))
                    }
                } catch (_: SerializationException) {
                    fetchAndStoreNullable(
                        cacheKey = cacheKey,
                        region = region,
                        ttl = ttl,
                        serializer = serializer,
                        fetch = fetch,
                    )
                }
            }
        }
        return fetchAndStoreNullable(
            cacheKey = cacheKey,
            region = region,
            ttl = ttl,
            serializer = serializer,
            fetch = fetch,
        )
    }

    private suspend fun <T> fetchAndStore(
        cacheKey: String,
        region: String,
        ttl: Duration,
        serializer: KSerializer<T>,
        fetch: suspend () -> AppResult<T>,
    ): AppResult<T> {
        val result = fetch()
        if (result is AppResult.Success) {
            cache.put(
                provider = id,
                region = region,
                cacheKey = cacheKey,
                payloadJson = json.encodeToString(serializer, result.data),
                expiresAt = expiresAt(ttl),
            )
        }
        return result
    }

    private suspend fun <T> fetchAndStoreNullable(
        cacheKey: String,
        region: String,
        ttl: Duration,
        serializer: KSerializer<T>,
        fetch: suspend () -> AppResult<T?>,
    ): AppResult<T?> {
        val result = fetch()
        if (result is AppResult.Success) {
            val payload = result.data?.let { json.encodeToString(serializer, it) } ?: NULL_SENTINEL
            cache.put(
                provider = id,
                region = region,
                cacheKey = cacheKey,
                payloadJson = payload,
                expiresAt = expiresAt(ttl),
            )
        }
        return result
    }

    private fun bookCacheKey(asin: String): String = "book:$asin"

    private fun expiresAt(ttl: Duration): Long = (clock.now() + ttl).toEpochMilliseconds()

    private companion object {
        const val NULL_SENTINEL = "null"

        /** Book metadata: 7 days. */
        val BOOK_TTL = 7.days

        /** Chapters: 30 days (they rarely change). */
        val CHAPTER_TTL = 30.days

        /** Author profiles: 7 days. */
        val AUTHOR_TTL = 7.days

        /** Author search: 24 h. */
        val SEARCH_TTL = 24.hours
    }
}
