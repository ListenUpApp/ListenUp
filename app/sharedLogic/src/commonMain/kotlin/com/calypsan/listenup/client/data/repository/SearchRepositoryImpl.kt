
package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.client.data.local.db.BookSearchResult
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.data.local.db.TagEntity
import com.calypsan.listenup.client.data.local.db.coverPathFor
import com.calypsan.listenup.client.data.repository.common.QueryUtils
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
 * Repository for search operations, backed entirely by the local Room FTS5 index.
 *
 * The server runs the identical FTS5 algorithm over the same library, so for a client that already
 * holds the library in Room there is nothing to gain from a network round-trip — search reads the
 * local index directly. This is faster, works offline by construction, and is correctly scoped to
 * the books this client can see. (The server's REST search endpoint remains for data-less clients,
 * e.g. web, that have no local index.)
 *
 * The local index is kept in sync by [com.calypsan.listenup.client.data.sync.FtsPopulator], which
 * rebuilds it after each catch-up/scan and self-heals an empty index on startup.
 *
 * @property searchDao Local FTS5 search DAO
 * @property imageStorage For resolving local cover paths
 */
internal class SearchRepositoryImpl(
    private val searchDao: SearchDao,
    private val imageStorage: ImageStorage,
) : com.calypsan.listenup.client.domain.repository.SearchRepository {
    /**
     * Search across books, contributors, series, and tags against the local FTS5 index.
     *
     * @param query Search query string
     * @param types Types to search (null = all)
     * @param genres Unused locally (server-only genre faceting); accepted for interface parity
     * @param genrePath Unused locally; accepted for interface parity
     * @param limit Max results per type
     */
    override suspend fun search(
        query: String,
        types: List<SearchHitType>?,
        genres: List<String>?,
        genrePath: String?,
        limit: Int,
    ): SearchResult {
        val sanitizedQuery = QueryUtils.sanitize(query)
        if (sanitizedQuery.isBlank()) {
            return SearchResult(
                query = query,
                total = 0,
                tookMs = 0,
                hits = emptyList(),
            )
        }
        return searchLocal(sanitizedQuery, types, limit)
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
                            addAll(
                                safeSearch("Book FTS") {
                                    searchDao.searchBooks(ftsQuery, limit).map { it.toSearchHit(imageStorage) }
                                },
                            )
                        }

                        if (SearchHitType.CONTRIBUTOR in searchTypes) {
                            addAll(
                                safeSearch("Contributor FTS") {
                                    searchDao.searchContributors(ftsQuery, limit / 2).map { it.toSearchHit() }
                                },
                            )
                        }

                        if (SearchHitType.SERIES in searchTypes) {
                            addAll(
                                safeSearch("Series FTS") {
                                    searchDao.searchSeries(ftsQuery, limit / 2).map { it.toSearchHit() }
                                },
                            )
                        }

                        if (SearchHitType.TAG in searchTypes) {
                            // Tags use simple LIKE query, not FTS - use original query without *
                            addAll(
                                safeSearch("Tag") {
                                    searchDao.searchTags(query, limit / 2).map { it.toSearchHit() }
                                },
                            )
                        }
                    }
                }

            SearchResult(
                query = query,
                total = result.size,
                tookMs = duration.inWholeMilliseconds,
                hits = result,
                facets = SearchFacets(), // No facets in local search
                isOfflineResult = false,
            )
        }

    /**
     * Run one per-type local FTS fetch, isolating its failure so a single failing
     * type can't sink the whole [searchLocal] result. Re-throws
     * [CancellationException] to preserve structured concurrency.
     */
    private inline fun safeSearch(
        label: String,
        fetch: () -> List<SearchHit>,
    ): List<SearchHit> =
        try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "$label search failed" }
            emptyList()
        }
}

// --- Extension functions for mapping ---

private fun BookSearchResult.toSearchHit(imageStorage: ImageStorage): SearchHit {
    val coverPath = imageStorage.coverPathFor(book.id, book.coverDownloadedAt)

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
        coverHash = book.coverHash,
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
        name = name,
        bookCount = null,
        score = 1.0f,
    )
