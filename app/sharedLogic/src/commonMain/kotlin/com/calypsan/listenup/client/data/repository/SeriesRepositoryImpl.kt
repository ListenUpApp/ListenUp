package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.data.local.db.toListItem
import com.calypsan.listenup.client.data.local.db.SeriesMembershipRow
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.SeriesBookRef
import com.calypsan.listenup.client.domain.model.SeriesHierarchy
import com.calypsan.listenup.domain.series.SeriesMembership
import com.calypsan.listenup.domain.series.SeriesNode
import com.calypsan.listenup.domain.series.SeriesTree
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.client.data.repository.common.QueryUtils
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.model.MIN_SEARCH_QUERY_LENGTH
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesLineage
import com.calypsan.listenup.client.domain.model.SeriesSearchResponse
import com.calypsan.listenup.client.domain.model.SeriesSearchResult
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
 * - Search via the local FTS5 index (offline-first; no network round trip)
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
        when (val result = channel.call(idempotent = true) { it.getSeries(id) }) {
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
     * The Library's top-level series, each carrying its whole subtree's books.
     *
     * Composed from two DAO Flows so the projected books carry real authors/narrators via the
     * canonical [toListItem] mapper: the series-side flow supplies every series with its book ids and
     * sequences, the book-side flow a single batched read of `BookWithContributors` for the whole
     * library, joined in memory by book id. This avoids N+1 queries at the cost of one redundant read
     * of all books — acceptable because the library view already loads them all on the same screen.
     *
     * Each root's books are its subtree's, distinct and in [SeriesTree.defaultBookOrder] — the order
     * its own page lists them in. A root whose subtree holds no visible book is omitted.
     */
    override fun observeRootSeriesWithBooks(): Flow<List<SeriesWithBooks>> =
        combine(
            seriesDao.observeAllWithBooks(),
            // conflate the book stream: during initial population it invalidates once per inserted
            // book, and the combine below re-maps every book via toListItem (blocking cover stat) on
            // each — O(n²) allocation that OOMs. Collapse the burst to the latest snapshot.
            bookDao.observeAllWithContributors().conflate(),
        ) { seriesEntities, allBooksWithContributors ->
            val booksById = allBooksWithContributors.associateBy { it.book.id.value }
            val live = seriesEntities.filter { it.series.deletedAt == null }
            val tree = SeriesTree(live.map { SeriesNode(it.series.id.value, it.series.parentId, it.series.parentPosition) })
            // Held books never reach booksById: observeAllWithContributors excludes them in SQL, so a
            // membership of one is dropped here and never counted.
            val memberships =
                live.flatMap { entity ->
                    val sequences = entity.bookSequences.associate { it.bookId.value to it.sequence }
                    entity.books.mapNotNull { bookEntity ->
                        val id = bookEntity.id.value
                        booksById[id]?.let { book ->
                            SeriesMembership(id, entity.series.id.value, sequences[id], sortKey = book.book.title)
                        }
                    }
                }
            val listItems = HashMap<String, BookListItem>()
            live
                .filter { tree.ancestorsOf(it.series.id.value).isEmpty() }
                .map { root ->
                    val rootId = root.series.id.value
                    SeriesWithBooks(
                        series = root.series.toDomain(),
                        books =
                            tree.defaultBookOrder(rootId, memberships).mapNotNull { bookId ->
                                booksById[bookId]?.let { listItems.getOrPut(bookId) { it.toListItem(imageStorage) } }
                            },
                        bookSequences = root.bookSequences.associate { it.bookId.value to it.sequence },
                        subSeriesCount = tree.childrenOf(rootId).size,
                    )
                }
                // A root with no book the library shows — its books all held for review, or not
                // synced yet — would be an empty card.
                .filter { it.books.isNotEmpty() }
        }.flowOn(IODispatcher) // per-book toListItem does a blocking cover stat — keep it off the collector (Main).
            // Room invalidates the entire series+books result on any book or series write.
            // distinctUntilChanged drops re-emissions where the mapped List<SeriesWithBooks> is
            // structurally equal to the last — SeriesWithBooks and BookListItem are both data classes.
            .distinctUntilChanged()

    override fun observeHierarchy(): Flow<SeriesHierarchy> =
        combine(seriesDao.observeAll(), seriesDao.observeVisibleMemberships()) { entities, rows ->
            hierarchyOf(entities, rows)
        }.flowOn(IODispatcher)
            // Any book or series write re-runs the query; only a changed hierarchy is worth emitting.
            .distinctUntilChanged()

    private suspend fun currentHierarchy(): SeriesHierarchy =
        withContext(IODispatcher) { hierarchyOf(seriesDao.getAll(), seriesDao.getVisibleMemberships()) }

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

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeSeriesLineage(seriesId: String): Flow<SeriesLineage> =
        seriesDao
            .observeAll()
            .flatMapLatest { entities ->
                val resolver = SeriesLineageResolver(entities.map { it.toDomain() })
                if (!resolver.hasSubSeries(seriesId)) {
                    flowOf(resolver.resolve(seriesId, emptyList()))
                } else {
                    bookDao
                        .observeBySeriesIdsWithContributors(resolver.subtreeOf(seriesId).toList())
                        .map { rows -> resolver.resolve(seriesId, rows.map { it.toListItem(imageStorage) }) }
                }
            }.flowOn(IODispatcher) // per-book toListItem does a blocking cover stat — keep it off the collector (Main).
            // Any series write anywhere re-runs this; only a changed lineage is worth re-rendering.
            .distinctUntilChanged()

    override suspend fun searchSeries(
        query: String,
        limit: Int,
    ): SeriesSearchResponse {
        val sanitizedQuery = QueryUtils.sanitize(query)
        if (sanitizedQuery.isBlank() || sanitizedQuery.length < MIN_SEARCH_QUERY_LENGTH) {
            return SeriesSearchResponse(
                series = emptyList(),
                isOfflineResult = false,
                tookMs = 0,
            )
        }

        // Local-only: the client mirrors every series in Room and maintains its own FTS5 index, so a
        // series search never needs the network. Faster (no round trip) and the canonical offline-first
        // read path — Room is the single source of truth.
        return searchLocal(sanitizedQuery, limit)
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

            val hierarchy = currentHierarchy()
            val series =
                entities.map { entity ->
                    SeriesSearchResult(
                        id = entity.id.value,
                        name = entity.name,
                        bookCount = hierarchy.bookCount(entity.id.value),
                        parentPath = hierarchy.pathNames(entity.id.value),
                    )
                }

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

internal fun SeriesEntity.toDomain(): Series =
    Series(
        id = id,
        name = name,
        description = description,
        createdAt = createdAt,
        coverPath = coverPath,
        asin = asin,
        parentId = parentId?.let(::SeriesId),
        parentPosition = parentPosition,
    )

/** The hierarchy over the live rows of [entities] and the visible [rows]. */
internal fun hierarchyOf(
    entities: List<SeriesEntity>,
    rows: List<SeriesMembershipRow>,
): SeriesHierarchy =
    SeriesHierarchy(
        series = entities.filter { it.deletedAt == null }.map { it.toDomain() },
        memberships = rows.map { SeriesBookRef(it.seriesId, it.bookId) },
    )
