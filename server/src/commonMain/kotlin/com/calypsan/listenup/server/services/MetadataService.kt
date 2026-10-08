@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
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
 * A book found via [MetadataService.getBookInAnyRegion]: the [book] itself, together with the
 * [region] whose storefront actually answered — which may differ from the region requested.
 */
data class RegionalBook(
    val book: AudibleBook,
    val region: AudibleRegion,
)

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
 * Cache keys are scoped by provider + region inside [MetadataCacheRepository] via
 * the `"${provider}:${region}:${cacheKey}"` stored-key formula — this service is the
 * Audible writer, so it passes [MetadataProviderId.AUDIBLE]; callers provide only the
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
     *
     * Pass [refresh] = `true` to bypass the long-lived cache and force a fresh fetch —
     * the only way to pick up a corrected catalog list within the 30-day window.
     */
    suspend fun getBookChapters(
        region: AudibleRegion,
        asin: String,
        refresh: Boolean = false,
    ): AppResult<List<AudibleChapter>> =
        cached(
            region = region,
            cacheKey = "chapters:$asin",
            ttl = CHAPTER_TTL,
            refresh = refresh,
            fetch = { audible.getChapters(region, asin) },
            serializer = ListSerializer(AudibleChapter.serializer()),
        )

    /**
     * Looks up [asin] across Audible storefronts until one actually sells the book, returning it
     * together with the [AudibleRegion] whose storefront answered.
     *
     * Some ASINs are region-locked: Audible answers a title-less stub for a storefront that
     * doesn't carry the book, which [getBook] already folds into `Success(null)` (see
     * `RawProduct.isStub`) — that is a "not sold *here*", not a "doesn't exist" signal. So a
     * `Success(null)` from one store moves the walk on to the next; if every store answers that
     * way, the walk itself converges on `Success(null)`.
     *
     * A [AppResult.Failure] (network error, 5xx, rate limit) stops the walk immediately and is
     * returned as-is — trying the next store on a transient outage would silently mask it as
     * "this book isn't sold anywhere," which is a worse lie than just failing.
     *
     * Store order: [preferred] first, then US, CA, UK, AU, then every other [AudibleRegion] entry
     * — each store visited at most once.
     *
     * Reuses [getBook] per store, so its TTL cache and the per-region `AudibleRateLimiter` apply
     * unchanged.
     */
    suspend fun getBookInAnyRegion(
        asin: String,
        preferred: AudibleRegion,
        refresh: Boolean = false,
    ): AppResult<RegionalBook?> {
        for (region in regionWalkOrder(preferred)) {
            when (val result = getBook(region, asin, refresh)) {
                is AppResult.Failure -> return result
                is AppResult.Success -> result.data?.let { return AppResult.Success(RegionalBook(it, region)) }
            }
        }
        return AppResult.Success(null)
    }

    /** [preferred] first, then US/CA/UK/AU, then every remaining [AudibleRegion] entry — no duplicates. */
    private fun regionWalkOrder(preferred: AudibleRegion): List<AudibleRegion> {
        val priority =
            listOf(preferred, AudibleRegion.US, AudibleRegion.CA, AudibleRegion.UK, AudibleRegion.AU).distinct()
        return priority + AudibleRegion.entries.filterNot { it in priority }
    }

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
            val cachedJson = cache.get(MetadataProviderId.AUDIBLE, region.code, cacheKey)
            if (cachedJson != null) {
                return try {
                    AppResult.Success(json.decodeFromString(serializer, cachedJson))
                } catch (_: SerializationException) {
                    // Stale schema in cache — treat as miss and re-fetch.
                    fetchAndStore(
                        region = region,
                        cacheKey = cacheKey,
                        ttl = ttl,
                        fetch = fetch,
                        serializer = serializer,
                    )
                }
            }
        }
        return fetchAndStore(region = region, cacheKey = cacheKey, ttl = ttl, fetch = fetch, serializer = serializer)
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
            val cachedJson = cache.get(MetadataProviderId.AUDIBLE, region.code, cacheKey)
            if (cachedJson != null) {
                return decodeNullableOrRefetch(
                    cachedJson = cachedJson,
                    serializer = serializer,
                    region = region,
                    cacheKey = cacheKey,
                    ttl = ttl,
                    fetch = fetch,
                )
            }
        }
        return fetchAndStoreNullable(
            region = region,
            cacheKey = cacheKey,
            ttl = ttl,
            fetch = fetch,
            serializer = serializer,
        )
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
            fetchAndStoreNullable(
                region = region,
                cacheKey = cacheKey,
                ttl = ttl,
                fetch = fetch,
                serializer = serializer,
            )
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
            cache.put(
                provider = MetadataProviderId.AUDIBLE,
                region = region.code,
                cacheKey = cacheKey,
                payloadJson = json.encodeToString(serializer, result.data),
                expiresAt = expiresAt,
            )
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
            cache.put(
                provider = MetadataProviderId.AUDIBLE,
                region = region.code,
                cacheKey = cacheKey,
                payloadJson = payload,
                expiresAt = expiresAt,
            )
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
    }
}
