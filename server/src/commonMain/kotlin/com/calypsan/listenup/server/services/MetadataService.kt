@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleContributorProfile
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val log = loggerFor<MetadataService>()

/**
 * Orchestrator for external metadata lookups. Wraps [AudibleApi] + [ITunesApi]
 * with TTL caching through [MetadataCacheRepository]. Implements region-aware
 * fallback: the configured [defaultRegion] is tried first; if results are empty
 * or the region matches the default, no fallback is attempted — otherwise the
 * default region is retried as a last resort.
 *
 * Cache TTLs:
 * - search results: 24 h
 * - book metadata: 7 days
 * - chapters: 30 days
 *
 * Cache keys are scoped by region inside [MetadataCacheRepository] via the
 * `"${region.code}:${cacheKey}"` stored-key formula — callers provide only the
 * logical key (e.g. `"book:B0015T963C"`).
 *
 * [ITunesApi] (cover art) is **not** cached at this layer — cover look-ups are
 * fast single requests and results vary by caller intent (title + author query).
 */
internal class MetadataService(
    private val audible: AudibleApi,
    private val itunes: ITunesApi,
    private val cache: MetadataCacheRepository,
    private val defaultRegion: AudibleRegion = AudibleRegion.US,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clock: Clock = Clock.System,
) {
    /**
     * Searches the Audible catalog using [params] in [region], caching the
     * result for [SEARCH_TTL].
     *
     * Pass [refresh] = `true` to bypass the cache and force a fresh fetch.
     * Only keyword-based searches are cached; searches with no keywords skip the cache entirely.
     */
    suspend fun search(
        region: AudibleRegion,
        params: SearchParams,
        refresh: Boolean = false,
    ): AppResult<List<AudibleSearchResult>> {
        val cacheKey =
            params.keywords?.takeIf { it.isNotBlank() }
                ?: return audible.search(region, params) // non-keyword search — no cache

        return cached(
            region = region,
            cacheKey = "search:$cacheKey",
            ttl = SEARCH_TTL,
            refresh = refresh,
            fetch = { audible.search(region, params) },
            serializer = ListSerializer(AudibleSearchResult.serializer()),
        )
    }

    /**
     * Searches with region fallback.
     *
     * Tries [defaultRegion] first. If results are non-empty, returns them with
     * the default region. If empty (or [defaultRegion] is US and results are
     * still empty), falls back to [AudibleRegion.US].
     */
    suspend fun searchWithFallback(params: SearchParams): AppResult<List<AudibleSearchResult>> {
        val primaryResult = search(defaultRegion, params)
        if (primaryResult is AppResult.Success && primaryResult.data.isNotEmpty()) {
            return primaryResult
        }
        if (defaultRegion == AudibleRegion.US) return primaryResult
        when (primaryResult) {
            is AppResult.Failure -> {
                log.warn {
                    "metadata provider failed: source=AUDIBLE region=$defaultRegion " +
                        "(${primaryResult.error.code}) — falling back to US"
                }
            }

            is AppResult.Success -> {
                log.debug {
                    "metadata lookup: source=AUDIBLE region=$defaultRegion returned 0 results — falling back to US"
                }
            }
        }
        return search(AudibleRegion.US, params)
    }

    /**
     * Fetches full metadata for an audiobook by [asin] in [region], caching
     * the result (including `null` for 404) for [BOOK_TTL].
     *
     * Pass [refresh] = `true` to bypass the cache and force a fresh fetch.
     */
    suspend fun getBook(
        region: AudibleRegion,
        asin: String,
        refresh: Boolean = false,
    ): AppResult<AudibleBook?> =
        cachedNullable(
            region = region,
            cacheKey = "book:$asin",
            ttl = BOOK_TTL,
            refresh = refresh,
            fetch = { audible.getBook(region, asin) },
            serializer = AudibleBook.serializer(),
        )

    /**
     * Fetches the chapter list for an audiobook by [asin] in [region], caching
     * the result for [CHAPTER_TTL] (30 days — chapters rarely change).
     */
    suspend fun getBookChapters(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<AudibleChapter>> =
        cached(
            region = region,
            cacheKey = "chapters:$asin",
            ttl = CHAPTER_TTL,
            refresh = false,
            fetch = { audible.getChapters(region, asin) },
            serializer = ListSerializer(AudibleChapter.serializer()),
        )

    /**
     * Fetches the contributor profile for [asin] in [region], caching the result
     * (including `null` for unknown ASINs) for [ENTITY_TTL] (7 days).
     *
     * Pass [refresh] = `true` to bypass the cache and force a fresh page scrape.
     * Cache key is `"contributor:$asin"`, scoped by region via the repository.
     */
    suspend fun getContributor(
        region: AudibleRegion,
        asin: String,
        refresh: Boolean = false,
    ): AppResult<AudibleContributorProfile?> =
        cachedNullable(
            region = region,
            cacheKey = "contributor:$asin",
            ttl = ENTITY_TTL,
            refresh = refresh,
            fetch = { audible.getContributor(region, asin) },
            serializer = AudibleContributorProfile.serializer(),
        )

    /**
     * Searches Audible for contributors matching [name] in [region], caching
     * the result for [SEARCH_TTL].
     *
     * Uses HTML scraping at `www.audible.{tld}/search?searchAuthor={name}` — the
     * official catalog API offers no contributor-search endpoint.
     */
    suspend fun searchContributors(
        region: AudibleRegion,
        name: String,
    ): AppResult<List<AudibleContributorProfile>> =
        cached(
            region = region,
            cacheKey = "contributor-search:${name.trim().lowercase()}",
            ttl = SEARCH_TTL,
            refresh = false,
            fetch = { audible.searchContributors(region, name) },
            serializer = ListSerializer(AudibleContributorProfile.serializer()),
        )

    /**
     * Delegates cover-art lookup to [ITunesApi]. iTunes is uncached at this
     * layer — cover fetches are fast and are only called when the caller
     * explicitly wants to enrich a book's cover.
     */
    suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?> = itunes.findCover(title, author)

    /**
     * Delegates multi-candidate cover-art search to [ITunesApi]. Uncached at this
     * layer — like [findCover], cover searches are fast single requests.
     */
    suspend fun searchCovers(
        title: String,
        author: String,
    ): AppResult<List<ITunesCoverHit>> = itunes.searchCovers(title, author)

    // ── Private caching helpers ───────────────────────────────────────────────

    /**
     * Checks the cache for a non-nullable [T] value at ([region], [cacheKey]).
     * On a miss (or when [refresh] is `true`), calls [fetch], caches a success
     * result for [ttl], and returns the [AppResult].
     *
     * A [SerializationException] from a stale-schema cache entry is treated as a
     * miss: the entry is effectively discarded and a fresh fetch is issued.
     */
    private suspend fun <T> cached(
        region: AudibleRegion,
        cacheKey: String,
        ttl: Duration,
        refresh: Boolean,
        fetch: suspend () -> AppResult<T>,
        serializer: KSerializer<T>,
    ): AppResult<T> {
        if (!refresh) {
            val cachedJson = cache.get(cacheKey, region)
            if (cachedJson != null) {
                return try {
                    AppResult.Success(json.decodeFromString(serializer, cachedJson))
                } catch (_: SerializationException) {
                    // Stale schema in cache — treat as miss and re-fetch.
                    fetchAndStore(region, cacheKey, ttl, fetch, serializer)
                }
            }
        }
        return fetchAndStore(region, cacheKey, ttl, fetch, serializer)
    }

    /**
     * Like [cached] but for nullable [T]. A `null` result (e.g. Audible 404)
     * is stored as the JSON sentinel `"null"` so repeated lookups for unknown
     * ASINs don't hammer the external API.
     */
    private suspend fun <T> cachedNullable(
        region: AudibleRegion,
        cacheKey: String,
        ttl: Duration,
        refresh: Boolean,
        fetch: suspend () -> AppResult<T?>,
        serializer: KSerializer<T>,
    ): AppResult<T?> {
        if (!refresh) {
            val cachedJson = cache.get(cacheKey, region)
            if (cachedJson != null) {
                return decodeNullableOrRefetch(cachedJson, serializer, region, cacheKey, ttl, fetch)
            }
        }
        return fetchAndStoreNullable(region, cacheKey, ttl, fetch, serializer)
    }

    /**
     * Decodes [cachedJson] into [T?], treating the sentinel `"null"` as a cached
     * null result. Falls back to [fetchAndStoreNullable] when the JSON is unparseable
     * (stale schema in cache).
     */
    private suspend fun <T> decodeNullableOrRefetch(
        cachedJson: String,
        serializer: KSerializer<T>,
        region: AudibleRegion,
        cacheKey: String,
        ttl: Duration,
        fetch: suspend () -> AppResult<T?>,
    ): AppResult<T?> =
        try {
            if (cachedJson == "null") {
                AppResult.Success(null)
            } else {
                AppResult.Success(json.decodeFromString(serializer, cachedJson))
            }
        } catch (_: SerializationException) {
            fetchAndStoreNullable(region, cacheKey, ttl, fetch, serializer)
        }

    private suspend fun <T> fetchAndStore(
        region: AudibleRegion,
        cacheKey: String,
        ttl: Duration,
        fetch: suspend () -> AppResult<T>,
        serializer: KSerializer<T>,
    ): AppResult<T> {
        val result = fetch()
        if (result is AppResult.Success) {
            val expiresAt = (clock.now() + ttl).toEpochMilliseconds()
            cache.put(cacheKey, region, json.encodeToString(serializer, result.data), expiresAt)
        }
        return result
    }

    private suspend fun <T> fetchAndStoreNullable(
        region: AudibleRegion,
        cacheKey: String,
        ttl: Duration,
        fetch: suspend () -> AppResult<T?>,
        serializer: KSerializer<T>,
    ): AppResult<T?> {
        val result = fetch()
        if (result is AppResult.Success) {
            val expiresAt = (clock.now() + ttl).toEpochMilliseconds()
            val data = result.data
            val payload = if (data == null) "null" else json.encodeToString(serializer, data)
            cache.put(cacheKey, region, payload, expiresAt)
        }
        return result
    }

    private companion object {
        /** Search results: 24 h. Go: `searchCacheDuration = 24 * time.Hour`. */
        val SEARCH_TTL = 24.hours

        /** Book metadata: 7 days. Go: `bookCacheDuration = 7 * 24 * time.Hour`. */
        val BOOK_TTL = 7.days

        /** Chapters: 30 days. Go: `chapterCacheDuration = 30 * 24 * time.Hour`. */
        val CHAPTER_TTL = 30.days

        /**
         * Contributor profiles: 7 days. Profiles change rarely; cached alongside
         * book metadata at the same TTL for consistency.
         */
        val ENTITY_TTL = 7.days
    }
}
