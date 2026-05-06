package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.IODispatcher
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.data.local.db.toListItem
import com.calypsan.listenup.client.data.remote.SeriesApiContract
import com.calypsan.listenup.client.data.repository.common.QueryUtils
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesSearchResponse
import com.calypsan.listenup.client.domain.model.SeriesSearchResult
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.core.error.AppException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.measureTimedValue

private val logger = KotlinLogging.logger {}

/**
 * Implementation of the domain SeriesRepository using Room.
 *
 * Provides:
 * - Reactive (Flow-based) and one-shot queries for series
 * - Library view methods (series with their books)
 * - Series detail methods
 * - Search with "never stranded" pattern (server with local fallback)
 *
 * @property seriesDao Room DAO for series operations
 * @property bookDao Room DAO for book queries that include contributor joins,
 *   used to populate the [BookListItem]s carried by [SeriesWithBooks]
 * @property searchDao Room DAO for FTS search
 * @property api Server API client for series search
 * @property networkMonitor For checking online/offline status
 * @property imageStorage For resolving cover image paths
 */
class SeriesRepositoryImpl(
    private val seriesDao: SeriesDao,
    private val bookDao: BookDao,
    private val searchDao: SearchDao,
    private val api: SeriesApiContract,
    private val networkMonitor: NetworkMonitor,
    private val imageStorage: ImageStorage,
) : SeriesRepository {
    // ========== Basic Observation Methods ==========

    override fun observeAll(): Flow<List<Series>> =
        seriesDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }

    override fun observeById(id: String): Flow<Series?> =
        seriesDao.observeById(id).map { entity ->
            entity?.toDomain()
        }

    override suspend fun getById(id: String): Series? = seriesDao.getById(id)?.toDomain()

    override fun observeByBookId(bookId: String): Flow<Series?> =
        seriesDao.observeByBookId(bookId).map { entity ->
            entity?.toDomain()
        }

    override suspend fun getBookIdsForSeries(seriesId: String): List<String> = seriesDao.getBookIdsForSeries(seriesId)

    override fun observeBookIdsForSeries(seriesId: String): Flow<List<String>> =
        seriesDao.observeBookIdsForSeries(seriesId)

    // ========== Library View Methods ==========

    /**
     * Compose [SeriesWithBooks] from two DAO Flows so the projected books carry
     * real authors/narrators via the canonical [toListItem] mapper.
     *
     * The series-side flow supplies series + book ids + sequences; the book-side
     * flow supplies a single batched read of `BookWithContributors` for the entire
     * library, joined in-memory by book id. This avoids N+1 queries (one
     * contributor query per series) at the cost of a single redundant read of
     * all books — acceptable because the library view already loads all books
     * elsewhere on the same screen.
     */
    override fun observeAllWithBooks(): Flow<List<SeriesWithBooks>> =
        combine(
            seriesDao.observeAllWithBooks(),
            bookDao.observeAllWithContributors(),
        ) { seriesEntities, allBooksWithContributors ->
            val booksById = allBooksWithContributors.associateBy { it.book.id }
            seriesEntities.map { entity ->
                val books =
                    entity.books.mapNotNull { bookEntity ->
                        val resolved = booksById[bookEntity.id]?.toListItem(imageStorage)
                        if (resolved == null) {
                            logger.debug {
                                "Skipping orphan book ${bookEntity.id} for series ${entity.series.id}"
                            }
                        }
                        resolved
                    }
                val sequences =
                    entity.bookSequences.associate {
                        it.bookId.value to it.sequence
                    }
                SeriesWithBooks(
                    series = entity.series.toDomain(),
                    books = books,
                    bookSequences = sequences,
                )
            }
        }

    // ========== Series Detail Methods ==========

    /**
     * Compose the detail-screen [SeriesWithBooks] by combining the series
     * relation (for sequences) with the contributor-enriched book query, so the
     * books carry real authors/narrators for surfaces that display them
     * (e.g. the narrow series-detail list).
     */
    override fun observeSeriesWithBooks(seriesId: String): Flow<SeriesWithBooks?> =
        combine(
            seriesDao.observeByIdWithBooks(seriesId),
            bookDao.observeBySeriesIdWithContributors(seriesId),
        ) { seriesRelation, booksWithContributors ->
            seriesRelation?.let { relation ->
                val books = booksWithContributors.map { it.toListItem(imageStorage) }
                val sequences =
                    relation.bookSequences.associate { seq ->
                        seq.bookId.value to seq.sequence
                    }
                SeriesWithBooks(
                    series = relation.series.toDomain(),
                    books = books,
                    bookSequences = sequences,
                )
            }
        }

    // ========== Search Methods ==========

    override suspend fun searchSeries(
        query: String,
        limit: Int,
    ): SeriesSearchResponse {
        val sanitizedQuery = QueryUtils.sanitize(query)
        if (sanitizedQuery.isBlank() || sanitizedQuery.length < 2) {
            return SeriesSearchResponse(
                series = emptyList(),
                isOfflineResult = false,
                tookMs = 0,
            )
        }

        // Try server search if online
        if (networkMonitor.isOnline()) {
            try {
                return searchServer(sanitizedQuery, limit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Server series search failed, falling back to local FTS" }
            }
        }

        // Offline or server failed - use local FTS
        return searchLocal(sanitizedQuery, limit)
    }

    private suspend fun searchServer(
        query: String,
        limit: Int,
    ): SeriesSearchResponse =
        withContext(IODispatcher) {
            val (series, duration) =
                measureTimedValue {
                    when (val result = api.searchSeries(query, limit)) {
                        is Success -> result.data.map { it.toDomain() }
                        is Failure -> throw AppException(result.error)
                    }
                }

            logger.debug {
                "Server series search: query='$query', results=${series.size}, took=${duration.inWholeMilliseconds}ms"
            }

            SeriesSearchResponse(
                series = series,
                isOfflineResult = false,
                tookMs = duration.inWholeMilliseconds,
            )
        }

    private suspend fun searchLocal(
        query: String,
        limit: Int,
    ): SeriesSearchResponse =
        withContext(IODispatcher) {
            val (entities, duration) =
                measureTimedValue {
                    val ftsQuery = QueryUtils.toFtsQuery(query)
                    try {
                        searchDao.searchSeries(ftsQuery, limit)
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Series FTS search failed" }
                        emptyList()
                    }
                }

            val series = entities.map { it.toSearchResult() }

            logger.debug {
                "Local series search: query='$query', results=${series.size}, took=${duration.inWholeMilliseconds}ms"
            }

            SeriesSearchResponse(
                series = series,
                isOfflineResult = true,
                tookMs = duration.inWholeMilliseconds,
            )
        }
}

// ========== Entity to Domain Mappers ==========

private fun SeriesEntity.toDomain(): Series =
    Series(
        id = id,
        name = name,
        description = description,
        createdAt = createdAt,
    )

private fun SeriesEntity.toSearchResult(): SeriesSearchResult =
    SeriesSearchResult(
        id = id.value,
        name = name,
        bookCount = 0, // Not available in offline mode
    )

private fun com.calypsan.listenup.client.data.remote.SeriesSearchResult.toDomain(): SeriesSearchResult =
    SeriesSearchResult(
        id = id,
        name = name,
        bookCount = bookCount,
    )
