@file:Suppress("CognitiveComplexMethod")

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.client.data.local.db.BookSearchResult
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.data.local.db.TagEntity
import com.calypsan.listenup.client.data.remote.SearchApiContract
import com.calypsan.listenup.client.data.remote.SearchFacetsResponse
import com.calypsan.listenup.client.data.remote.SearchHitResponse
import com.calypsan.listenup.client.data.remote.SearchResponse
import com.calypsan.listenup.client.data.repository.common.QueryUtils
import com.calypsan.listenup.client.domain.model.FacetCount
import com.calypsan.listenup.client.domain.model.SearchFacets
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult
import com.calypsan.listenup.client.domain.repository.ImageStorage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.time.measureTimedValue

private val logger = KotlinLogging.logger {}

/**
 * Repository for search operations.
 *
 * Implements "never stranded" pattern:
 * - Primary: Use server Bleve search (fuzzy, faceted, hierarchical)
 * - Fallback: Local Room FTS5 (simpler but always works)
 *
 * Always tries server first because the server may be on a local network
 * without internet access. Falls back to local search on any error.
 *
 * The caller doesn't need to know which path was taken—both return
 * the same SearchResult type. The `isOfflineResult` flag indicates
 * which was used (for optional UI indication).
 *
 * @property searchApi Server search API client
 * @property searchDao Local FTS5 search DAO
 * @property imageStorage For resolving local cover paths
 */
class SearchRepositoryImpl(
    private val searchApi: SearchApiContract,
    private val searchDao: SearchDao,
    private val imageStorage: ImageStorage,
) : com.calypsan.listenup.client.domain.repository.SearchRepository {
    /**
     * Search across books, contributors, and series.
     *
     * Tries server search first if online. Falls back to local FTS
     * on network error or if offline.
     *
     * @param query Search query string
     * @param types Types to search (null = all)
     * @param genres Genre slugs to filter by
     * @param genrePath Genre path prefix for hierarchical filtering
     * @param limit Max results per type
     */
    override suspend fun search(
        query: String,
        types: List<SearchHitType>?,
        genres: List<String>?,
        genrePath: String?,
        limit: Int,
    ): SearchResult {
        // Sanitize query
        val sanitizedQuery = QueryUtils.sanitize(query)
        if (sanitizedQuery.isBlank()) {
            return SearchResult(
                query = query,
                total = 0,
                tookMs = 0,
                hits = emptyList(),
            )
        }

        // Always try server search first, fall back to local on failure
        // We don't check isOnline() because the server may be on a local network
        // without internet access, which would cause isOnline() to return false
        // even though the server is reachable.
        return try {
            searchServer(sanitizedQuery, types, genres, genrePath, limit)
        } catch (e: CancellationException) {
            // Preserve structured concurrency - re-throw cancellation
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Server search failed for '$sanitizedQuery', falling back to local search" }
            searchLocal(sanitizedQuery, types, limit)
        }
    }

    /**
     * Server-side Bleve search.
     */
    private suspend fun searchServer(
        query: String,
        types: List<SearchHitType>?,
        genres: List<String>?,
        genrePath: String?,
        limit: Int,
    ): SearchResult =
        withContext(IODispatcher) {
            val typesParam = types?.joinToString(",") { it.name.lowercase() }
            val genresParam = genres?.joinToString(",")

            val response =
                searchApi.search(
                    query = query,
                    types = typesParam,
                    genres = genresParam,
                    genrePath = genrePath,
                    limit = limit,
                )

            response.toDomain(imageStorage)
        }

    /**
     * Local Room FTS5 search.
     */
    private suspend fun searchLocal(
        query: String,
        types: List<SearchHitType>?,
        limit: Int,
    ): SearchResult =
        withContext(IODispatcher) {
            val (result, duration) =
                measureTimedValue {
                    val ftsQuery = QueryUtils.toFtsQuery(query)
                    val searchTypes = types ?: SearchHitType.entries

                    buildList {
                        // Search each type
                        if (SearchHitType.BOOK in searchTypes) {
                            try {
                                val bookResults = searchDao.searchBooks(ftsQuery, limit)
                                addAll(bookResults.map { it.toSearchHit(imageStorage) })
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.warn(e) { "Book FTS search failed" }
                            }
                        }

                        if (SearchHitType.CONTRIBUTOR in searchTypes) {
                            try {
                                val contributors = searchDao.searchContributors(ftsQuery, limit / 2)
                                addAll(contributors.map { it.toSearchHit() })
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.warn(e) { "Contributor FTS search failed" }
                            }
                        }

                        if (SearchHitType.SERIES in searchTypes) {
                            try {
                                val series = searchDao.searchSeries(ftsQuery, limit / 2)
                                addAll(series.map { it.toSearchHit() })
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.warn(e) { "Series FTS search failed" }
                            }
                        }

                        if (SearchHitType.TAG in searchTypes) {
                            try {
                                // Tags use simple LIKE query, not FTS - use original query without *
                                val tags = searchDao.searchTags(query, limit / 2)
                                addAll(tags.map { it.toSearchHit() })
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.warn(e) { "Tag search failed" }
                            }
                        }
                    }
                }

            SearchResult(
                query = query,
                total = result.size,
                tookMs = duration.inWholeMilliseconds,
                hits = result,
                facets = SearchFacets(), // No facets in local search
                isOfflineResult = true,
            )
        }
}

// --- Extension functions for mapping ---

private fun SearchResponse.toDomain(imageStorage: ImageStorage): SearchResult =
    SearchResult(
        query = query,
        total = total.toInt(),
        tookMs = tookMs,
        hits = hits.map { it.toDomain(imageStorage) },
        facets = facets?.toDomain() ?: SearchFacets(),
        isOfflineResult = false,
    )

private fun SearchHitResponse.toDomain(imageStorage: ImageStorage): SearchHit {
    val hitType =
        when (type.lowercase()) {
            "book" -> SearchHitType.BOOK
            "contributor" -> SearchHitType.CONTRIBUTOR
            "series" -> SearchHitType.SERIES
            "tag" -> SearchHitType.TAG
            else -> SearchHitType.BOOK
        }

    // Resolve cover path for books (convert String to BookId)
    val coverPath =
        if (hitType == SearchHitType.BOOK && id.isNotBlank()) {
            val bookId = BookId(id)
            if (imageStorage.exists(bookId)) imageStorage.getCoverPath(bookId) else null
        } else {
            null
        }

    return SearchHit(
        id = id,
        type = hitType,
        name = name,
        subtitle = subtitle,
        author = author,
        narrator = narrator,
        seriesName = seriesName,
        duration = duration,
        bookCount = bookCount,
        genreSlugs = genreSlugs,
        tags = tags,
        coverPath = coverPath,
        score = score,
        highlight = highlights?.values?.firstOrNull(),
    )
}

private fun SearchFacetsResponse.toDomain(): SearchFacets =
    SearchFacets(
        types = types?.map { FacetCount(it.value, it.count) } ?: emptyList(),
        genres = genres?.map { FacetCount(it.value, it.count) } ?: emptyList(),
        authors = authors?.map { FacetCount(it.value, it.count) } ?: emptyList(),
        narrators = narrators?.map { FacetCount(it.value, it.count) } ?: emptyList(),
    )

private fun BookSearchResult.toSearchHit(imageStorage: ImageStorage): SearchHit {
    val coverPath = if (imageStorage.exists(book.id)) imageStorage.getCoverPath(book.id) else null

    return SearchHit(
        id = book.id.value,
        type = SearchHitType.BOOK,
        name = book.title,
        subtitle = book.subtitle,
        author = authorName, // Author from FTS denormalized data
        narrator = null,
        seriesName = null, // Series now in junction table - acceptable for offline
        duration = book.totalDuration,
        bookCount = null,
        coverPath = coverPath,
        score = 1.0f, // No scoring in local search
    )
}

private fun ContributorEntity.toSearchHit(): SearchHit =
    SearchHit(
        id = id.value,
        type = SearchHitType.CONTRIBUTOR,
        name = name,
        bookCount = null, // Would need count - acceptable for offline
        score = 1.0f,
    )

private fun SeriesEntity.toSearchHit(): SearchHit =
    SearchHit(
        id = id.value,
        type = SearchHitType.SERIES,
        name = name,
        bookCount = null,
        score = 1.0f,
    )

private fun TagEntity.toSearchHit(): SearchHit =
    SearchHit(
        id = id,
        type = SearchHitType.TAG,
        name = displayName(),
        bookCount = bookCount,
        score = 1.0f,
    )
