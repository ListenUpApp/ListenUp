package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.dto.SearchQuery
import com.calypsan.listenup.api.dto.SeriesHit
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.data.local.db.toListItem
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.client.data.repository.common.QueryUtils
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesSearchResponse
import com.calypsan.listenup.client.domain.model.SeriesSearchResult
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
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
 * [observeById] layers a "Never Stranded" RPC-fallback on top of the Room
 * observable: when Room yields `null` (the series is not cached yet) and the
 * device is online, a single on-demand [SeriesService.getSeries] call is fired
 * and the result is written through Room via
 * [com.calypsan.listenup.client.data.sync.domains.seriesDomain]'s
 * `onCatchUpItem`. The Room Flow then re-emits with the
 * now-present series. Offline cache misses skip the RPC entirely and stay `null`.
 *
 * @property seriesDao Room DAO for series operations
 * @property bookDao Room DAO for book queries that include contributor joins,
 *   used to populate the [BookListItem]s carried by [SeriesWithBooks]
 * @property searchDao Room DAO for FTS search
 * @property networkMonitor For checking online/offline status
 * @property imageStorage For resolving cover image paths
 * @property channel [RpcChannel] over [com.calypsan.listenup.api.SeriesService]
 *   for on-demand cache-miss fetches (bounded, self-healing).
 * @property searchChannel [RpcChannel] over the unified [com.calypsan.listenup.api.SearchService]
 *   backing the never-stranded server series autocomplete (bounded, self-healing).
 * @property seriesSyncHandler Owns the atomic aggregate write-through used to
 *   cache an on-demand-fetched series into Room.
 */
internal class SeriesRepositoryImpl(
    private val seriesDao: SeriesDao,
    private val bookDao: BookDao,
    private val searchDao: SearchDao,
    private val networkMonitor: NetworkMonitor,
    private val imageStorage: ImageStorage,
    private val channel: RpcChannel<SeriesService>,
    private val searchChannel: RpcChannel<SearchService>,
    private val seriesSyncHandler: SyncDomainHandler<SeriesSyncPayload>,
) : SeriesRepository {
    // ========== Basic Observation Methods ==========

    override fun observeAll(): Flow<List<Series>> =
        seriesDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }

    /**
     * Observe a series by id, with a never-stranded cache-miss fallback.
     *
     * When Room yields `null` (the series is not yet cached) and the device is
     * online, fires a single on-demand [SeriesService.getSeries] fetch and writes
     * the result through Room via
     * [com.calypsan.listenup.client.data.sync.domains.seriesDomain]. The Room Flow then
     * re-emits with the now-present series. The fetch is fired at most once per
     * collection — [attemptedFetch] guards against a persistently-null emission
     * re-firing the RPC on a loop. Offline cache misses and RPC failures degrade
     * silently to continued `null` emissions ("Never Stranded").
     */
    override fun observeById(id: String): Flow<Series?> {
        val seriesId = SeriesId(id)
        var attemptedFetch = false
        return seriesDao
            .observeById(id)
            .onEach { entity ->
                if (entity == null && !attemptedFetch && networkMonitor.isOnline()) {
                    attemptedFetch = true
                    fetchAndCacheSeries(seriesId)
                }
            }.map { entity -> entity?.toDomain() }
    }

    /**
     * One-shot on-demand fetch for a cache-missing series. Dispatches through the
     * [channel] (which folds transport faults to a typed [AppResult.Failure] and
     * re-raises [kotlin.coroutines.cancellation.CancellationException], so structured
     * concurrency is preserved without a manual catch), and writes a returned entity
     * through Room via the shared sync handler. A [AppResult.Failure] is logged and
     * left as a cache miss — the observer keeps emitting `null` rather than crashing
     * ("Never Stranded").
     */
    private suspend fun fetchAndCacheSeries(id: SeriesId) {
        when (val result = channel.call { it.getSeries(id) }) {
            is AppResult.Success -> {
                val payload = result.data
                if (payload == null) {
                    logger.debug { "getSeries returned no series for $id — leaving cache miss" }
                } else {
                    // Never-stranded: a Room write-through failure must NOT propagate into the
                    // observing flow and kill the screen's collector. Swallow-and-log; the observer
                    // keeps emitting null. Cooperative cancellation still propagates.
                    try {
                        seriesSyncHandler.onCatchUpItem(payload, isTombstone = false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.warn(e) { "On-demand getSeries write-through failed for $id — leaving cache miss" }
                    }
                }
            }

            is AppResult.Failure -> {
                logger.warn { "On-demand getSeries failed for $id (${result.error.code}) — staying with cache miss" }
            }
        }
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
            // conflate the book stream: during initial population it invalidates once per inserted
            // book, and the combine below re-maps every book via toListItem (blocking cover stat) on
            // each — O(n²) allocation that OOMs. Collapse the burst to the latest snapshot.
            bookDao.observeAllWithContributors().conflate(),
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
        }.flowOn(IODispatcher) // per-book toListItem does a blocking cover stat — keep it off the collector (Main).
            // Room invalidates the entire series+books result on any book or series write.
            // distinctUntilChanged drops re-emissions where the mapped List<SeriesWithBooks> is
            // structurally equal to the last — SeriesWithBooks and BookListItem are both data classes.
            .distinctUntilChanged()

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

        // Try server search if online; on Failure fall back to local FTS (never-stranded pattern)
        if (networkMonitor.isOnline()) {
            val serverResult = searchServer(sanitizedQuery, limit)
            if (serverResult != null) return serverResult
        }

        // Offline or server failed - use local FTS
        return searchLocal(sanitizedQuery, limit)
    }

    /**
     * Attempt a server-side series search via the unified [SearchService], reading the `series`
     * slice of the [com.calypsan.listenup.api.dto.SearchResults] envelope. Returns `null` on
     * [AppResult.Failure] so the caller can fall back to local FTS (never-stranded pattern).
     */
    private suspend fun searchServer(
        query: String,
        limit: Int,
    ): SeriesSearchResponse? =
        withContext(IODispatcher) {
            val (result, duration) =
                measureTimedValue { searchChannel.call { it.search(SearchQuery(text = query, limit = limit)) } }

            when (result) {
                is AppResult.Success -> {
                    val series = result.data.series.map { it.toDomain() }
                    logger.debug {
                        "Server series search: query='$query', results=${series.size}, took=${duration.inWholeMilliseconds}ms"
                    }
                    SeriesSearchResponse(
                        series = series,
                        isOfflineResult = false,
                        tookMs = duration.inWholeMilliseconds,
                    )
                }

                is AppResult.Failure -> {
                    logger.warn { "Server series search failed, falling back to local FTS: ${result.error.message}" }
                    null
                }
            }
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
        coverPath = coverPath,
        coverBlurHash = coverBlurHash,
        asin = asin,
    )

private fun SeriesEntity.toSearchResult(): SeriesSearchResult =
    SeriesSearchResult(
        id = id.value,
        name = name,
        bookCount = 0, // Not available in offline mode
    )

private fun SeriesHit.toDomain(): SeriesSearchResult =
    SeriesSearchResult(
        id = id.value,
        name = name,
        bookCount = bookCount,
    )
