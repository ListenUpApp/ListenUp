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
import com.calypsan.listenup.server.metadata.spi.ContributorSource
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
    ContributorSource,
    ChapterSource,
    CoverSource,
    SeriesSource,
    GenreSource {
    override val id: MetadataProviderId = MetadataProviderId.AUDNEXUS

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
        return fetchBook(asin, locale.region, refresh = false).map { it?.genres?.toGenreMetas() }
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
        return fetchBook(asin, locale.region, refresh = false).map { it?.toCoverMetas() ?: emptyList() }
    }

    override suspend fun getChapters(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<ChapterListMeta?> {
        val asin = book.asin ?: return AppResult.Success(null)
        return cachedNullable(
            "chapters:$asin",
            locale.region,
            CHAPTER_TTL,
            refresh = false,
            AudnexusChapters.serializer(),
        ) {
            client.getChapters(asin, locale.region)
        }.map { it?.toChapterListMeta() }
    }

    override suspend fun searchContributors(
        name: String,
        locale: MetadataLocale,
    ): AppResult<List<ContributorHitMeta>> =
        cached(
            "author-search:${name.trim().lowercase()}",
            locale.region,
            SEARCH_TTL,
            ListSerializer(AudnexusAuthor.serializer()),
        ) { client.searchAuthors(name, locale.region) }
            .map { hits -> hits.mapNotNull { it.toContributorHitMetaOrNull() } }

    override suspend fun getContributor(
        key: String,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ContributorMeta?> =
        cachedNullable("author:$key", locale.region, AUTHOR_TTL, refresh, AudnexusAuthorProfile.serializer()) {
            client.getAuthor(key, locale.region)
        }.map { it?.toContributorMeta() }

    /** The `/books/{asin}` fetch shared by core, genres, series, and cover — cached once per ASIN+region. */
    private suspend fun fetchBook(
        asin: String,
        region: String,
        refresh: Boolean,
    ): AppResult<AudnexusBook?> =
        cachedNullable(
            "book:$asin",
            region,
            BOOK_TTL,
            refresh,
            AudnexusBook.serializer(),
        ) { client.getBook(asin, region) }

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
                fetchAndStore(cacheKey, region, ttl, serializer, fetch)
            }
        }
        return fetchAndStore(cacheKey, region, ttl, serializer, fetch)
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
                    fetchAndStoreNullable(cacheKey, region, ttl, serializer, fetch)
                }
            }
        }
        return fetchAndStoreNullable(cacheKey, region, ttl, serializer, fetch)
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
            cache.put(id, region, cacheKey, json.encodeToString(serializer, result.data), expiresAt(ttl))
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
            cache.put(id, region, cacheKey, payload, expiresAt(ttl))
        }
        return result
    }

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
