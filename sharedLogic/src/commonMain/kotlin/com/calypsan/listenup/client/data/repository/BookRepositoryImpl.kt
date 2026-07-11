package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.local.db.ChapterEntity
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.coverPathFor
import com.calypsan.listenup.client.data.local.db.toAudioFile
import com.calypsan.listenup.client.data.local.db.toDetail
import com.calypsan.listenup.client.data.local.db.toListItem
import com.calypsan.listenup.client.data.remote.BookRpcFactory
import com.calypsan.listenup.client.core.isFirstInSeries
import com.calypsan.listenup.client.data.repository.common.QueryUtils
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.repository.DiscoveryBook
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.result.getOrNull as wireResultOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Cohesive bundle of the Flow sources joined into a book's detail view by
 * [BookRepositoryImpl].
 *
 * Groups the three repositories that exist on the impl solely to be `combine`d
 * into [BookRepositoryImpl.observeBookDetail] / [BookRepositoryImpl.getBookDetail] —
 * genres ([genreRepository]), tags ([tagRepository]), and moods ([moodRepository]) —
 * into a single injected value so the constructor stays cohesive rather than
 * carrying three loose params.
 *
 * @property genreRepository Upstream Flow source for a book's genres, composed
 *   into the detail views so genre edits propagate to detail-screen consumers.
 * @property tagRepository Upstream Flow source for a book's tags, composed
 *   into the detail views so tag edits propagate to detail-screen consumers.
 * @property moodRepository Upstream Flow source for a book's moods, composed
 *   into the detail views so mood edits propagate to detail-screen consumers.
 */
internal data class BookDetailJoinSources(
    val genreRepository: GenreRepository,
    val tagRepository: TagRepository,
    val moodRepository: MoodRepository,
)

/**
 * Repository for book data operations.
 *
 * Implements offline-first pattern:
 * - UI observes Room database via Flow
 * - Writes update local database immediately
 * - Sync happens in background
 *
 * Single source of truth: Room database
 *
 * Transforms data layer (BookEntity) to domain layer (Book) for UI consumption.
 * Uses ImageStorage to resolve local cover file paths.
 *
 * Uses Room Relations to efficiently batch-load books with their contributors,
 * avoiding N+1 query problems when loading book lists.
 *
 * Two never-stranded RPC-fallback paths layer on top of the Room-observing core:
 * - [observeBookDetail] fetches an absent book on demand when online.
 * - [search] uses server FTS5 when online, local FTS5 otherwise.
 *
 * @property bookDao Room DAO for book operations
 * @property chapterDao Room DAO for chapter operations
 * @property audioFileDao Room DAO for audio-file operations
 * @property searchDao Room FTS5 DAO backing the offline [search] fallback.
 * @property transactionRunner Runs multi-table writes atomically
 * @property imageStorage Storage for resolving cover image paths
 * @property joinSources The genre/tag/mood Flow sources composed into the
 *   book-detail views — see [BookDetailJoinSources].
 * @property networkMonitor Snapshot online check gating the RPC fallbacks.
 * @property bookRpcFactory Supplies the [com.calypsan.listenup.api.BookService]
 *   RPC proxy for on-demand fetch and server-side search.
 * @property bookSyncDomainHandler Owns the atomic aggregate write-through used
 *   to cache an on-demand-fetched book into Room.
 */
internal class BookRepositoryImpl(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val audioFileDao: AudioFileDao,
    private val searchDao: SearchDao,
    private val transactionRunner: TransactionRunner,
    private val imageStorage: ImageStorage,
    private val joinSources: BookDetailJoinSources,
    private val networkMonitor: NetworkMonitor,
    private val bookRpcFactory: BookRpcFactory,
    private val bookSyncDomainHandler: SyncDomainHandler<BookSyncPayload>,
) : com.calypsan.listenup.client.domain.repository.BookRepository,
    BookIngestPort {
    private val logger = KotlinLogging.logger {}

    /**
     * No-op affordance for pull-to-refresh gestures.
     *
     * Book refresh is driven entirely by the SSE event stream. This method is a
     * deliberate no-op retained for the pull-to-refresh UI affordance in
     * [LibraryViewModel] until that surface is revisited in Books-C.
     */
    override suspend fun refreshBooks(): AppResult<Unit> = AppResult.Success(Unit)

    /**
     * Get chapters for a book from the local database.
     *
     * @param bookId The book ID
     * @return List of chapters
     */
    override suspend fun getChapters(bookId: String): List<Chapter> {
        val localChapters = chapterDao.getChaptersForBook(BookId(bookId))
        return localChapters.map { it.toDomain() }
    }

    override fun observeChapters(bookId: String): Flow<List<Chapter>> =
        chapterDao
            .observeChaptersForBook(BookId(bookId))
            .map { rows -> rows.map { it.toDomain() } }

    private fun ChapterEntity.toDomain(): Chapter =
        Chapter(
            id = id.value,
            title = title,
            duration = duration,
            startTime = startTime,
            partTitle = partTitle,
            bookTitle = bookTitle,
        )

    /**
     * Observe random unstarted books for discovery, series-aware.
     *
     * Includes a book iff it is standalone (no series edge) OR first-in-series
     * in at least one of its series. Mid-series books are excluded so the
     * Discover surface never surfaces a book whose story has already begun.
     * The result is shuffled on every emission so pull-to-refresh reorders it.
     */
    override fun observeRandomUnstartedBooks(limit: Int): Flow<List<DiscoveryBook>> =
        bookDao.observeUnstartedCandidatesWithSeries().map { rows ->
            rows
                .groupBy { it.id }
                .values
                .filter { bookRows ->
                    val sequences = bookRows.mapNotNull { it.sequence }
                    sequences.isEmpty() || sequences.any { isFirstInSeries(it) }
                }.map { bookRows -> bookRows.first() }
                .shuffled()
                .take(limit)
                .map { it.toDiscoveryBook(imageStorage) }
        }

    /**
     * Observe recently added books for discovery.
     *
     * Transforms data layer entities to domain DiscoveryBook models,
     * resolving local cover paths via ImageStorage.
     */
    override fun observeRecentlyAddedBooks(limit: Int): Flow<List<DiscoveryBook>> =
        bookDao.observeRecentlyAddedWithAuthor(limit).map { entities ->
            entities.map { entity ->
                entity.toDiscoveryBook(imageStorage)
            }
        }

    override fun observeBookListItems(): Flow<List<BookListItem>> =
        combine(
            bookDao
                .observeAllWithContributors()
                // conflate BEFORE the combine: during an initial-population burst the book table is
                // invalidated once per inserted book, and re-mapping the whole library on every one is
                // O(n²) allocation that exhausts the heap → OOM. Conflate collapses a burst into a
                // single re-map of the latest snapshot; the UI still converges to the correct final
                // list. (toListItem's coverPath is pure in-memory string construction now — no I/O —
                // but the conflate is still needed for the O(n²) allocation cost alone.)
                .conflate(),
            bookDao.observeBookIdsWithDocuments(),
        ) { rows, docIds ->
            val docIdSet = docIds.toSet()
            rows.map { it.toListItem(imageStorage, hasDocuments = it.book.id.value in docIdSet) }
        }
            // Mapping itself is now pure in-memory work, but keep it off Dispatchers.Main (the
            // Library screen's collector context) so a large library never blocks the UI thread.
            .flowOn(IODispatcher)
            // Room invalidates the entire result set on any book write (even a single row update).
            // distinctUntilChanged drops re-emissions where the mapped List<BookListItem> hasn't
            // actually changed — BookListItem is a data class so structural equality is used.
            .distinctUntilChanged()

    override suspend fun getBookListItem(id: String): BookListItem? =
        bookDao.getByIdWithContributors(BookId(id))?.toListItem(imageStorage)

    override suspend fun getBookListItems(ids: List<String>): List<BookListItem> {
        if (ids.isEmpty()) return emptyList()
        return bookDao
            .getByIdsWithContributors(ids.map { BookId(it) })
            .map { it.toListItem(imageStorage) }
    }

    override fun observeIsBookLive(id: String): Flow<Boolean> = bookDao.observeIsLive(BookId(id))

    override fun observeBookListItems(ids: List<String>): Flow<List<BookListItem>> {
        if (ids.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyList())
        return bookDao
            .observeByIdsWithContributors(ids.map { BookId(it) })
            .map { rows -> rows.map { it.toListItem(imageStorage) } }
    }

    /**
     * Observe a book's detail, with a never-stranded cache-miss fallback.
     *
     * Composes the Room book/genre/tag Flows exactly as before. The added
     * behaviour: when Room yields `null` (the book is not cached) and the
     * device is online, fire a single on-demand [BookService.getBook] fetch and
     * write the result through to Room via [bookSyncDomainHandler]. The Room
     * Flow then re-emits with the now-present book. The fetch is fired at most
     * once per collection — [attemptedFetch] guards against a persistently-null
     * emission re-firing the RPC on a loop. Offline cache misses skip the RPC
     * entirely; RPC failures degrade silently to a continued `null`.
     *
     * The populated book is the second emission, not the first — it cannot
     * arrive until the RPC roundtrip and Room write-through complete, so
     * callers should render a loading or skeleton state while the value is
     * `null`. A transient fetch failure is not retried within a single
     * collection — the guard fires the fetch at most once, so a failed fetch
     * leaves `null` until the flow is re-collected (e.g. the screen is
     * revisited).
     */
    override fun observeBookDetail(id: String): Flow<BookDetail?> {
        val bookId = BookId(id)
        var attemptedFetch = false
        return combine(
            bookDao.observeByIdWithContributors(bookId),
            joinSources.genreRepository.observeGenresForBook(id),
            joinSources.tagRepository.observeTagsForBook(id),
            joinSources.moodRepository.observeMoodsForBook(id),
        ) { row, genres, tags, moods ->
            val audioFiles = if (row != null) audioFileDao.getForBook(id).map { it.toAudioFile() } else emptyList()
            row?.toDetail(imageStorage, genres, tags, moods, audioFiles)
        }.onEach { detail ->
            if (detail == null && !attemptedFetch && networkMonitor.isOnline()) {
                attemptedFetch = true
                fetchAndCacheBook(bookId)
            }
        }
    }

    /**
     * One-shot on-demand fetch for a cache-missing book. Resolves the [BookService]
     * proxy, fetches the aggregate, and writes it through Room via the shared sync
     * handler. Any failure is logged and swallowed — the observer keeps emitting
     * `null` rather than crashing ("Never Stranded"). [CancellationException] is
     * re-thrown to preserve structured concurrency.
     */
    private suspend fun fetchAndCacheBook(bookId: BookId) {
        try {
            val payload = bookRpcFactory.bookService().getBook(bookId).wireResultOrNull()
            if (payload != null) {
                bookSyncDomainHandler.onCatchUpItem(payload, isTombstone = false)
            } else {
                logger.debug { "getBook returned no book for $bookId — leaving cache miss" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "On-demand getBook failed for $bookId, staying with cache miss" }
        }
    }

    override suspend fun getBookDetail(id: String): BookDetail? {
        val bookId = BookId(id)
        val row = bookDao.getByIdWithContributors(bookId) ?: return null
        val genres = joinSources.genreRepository.observeGenresForBook(id).first()
        val tags = joinSources.tagRepository.observeTagsForBook(id).first()
        val moods = joinSources.moodRepository.observeMoodsForBook(id).first()
        val audioFiles = audioFileDao.getForBook(id).map { it.toAudioFile() }
        return row.toDetail(imageStorage, genres, tags, moods, audioFiles)
    }

    /**
     * Search books, never-stranded: server FTS5 when online, local FTS5 otherwise.
     *
     * Emits exactly once. When online, asks the server for ranked [BookId]s and
     * hydrates them from Room — re-sorting the unordered Room result back into the
     * server's rank order. Server ids absent from Room are fetched on demand via
     * [fetchAndCacheBook] so a freshly-indexed book still appears in results. A
     * server failure (exception or empty result on error) falls back to local FTS.
     * When offline, goes straight to local FTS5.
     */
    override fun search(query: String): Flow<List<BookListItem>> =
        flow {
            if (query.isBlank()) {
                emit(emptyList())
                return@flow
            }
            val results =
                if (networkMonitor.isOnline()) {
                    searchServerOrLocal(query)
                } else {
                    searchLocal(query)
                }
            emit(results)
        }

    /**
     * Run the server FTS search and hydrate from Room in rank order. Any failure —
     * thrown or surfaced via [AppResult.Failure] — falls back to [searchLocal].
     * [CancellationException] is re-thrown to preserve structured concurrency.
     */
    private suspend fun searchServerOrLocal(query: String): List<BookListItem> =
        try {
            when (val result = bookRpcFactory.bookService().searchBooks(query, limit = SEARCH_LIMIT)) {
                is WireAppResult.Success -> {
                    hydrateRanked(result.data)
                }

                is WireAppResult.Failure -> {
                    logger.warn { "Server book search failed (${result.error.code}), falling back to local FTS" }
                    searchLocal(query)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Server book search threw, falling back to local FTS" }
            searchLocal(query)
        }

    /**
     * Hydrate [rankedIds] from Room, preserving the server's rank order. Ids absent
     * from Room are fetched on demand and written through, then re-hydrated, so a
     * newly-indexed book is never silently dropped from results.
     */
    private suspend fun hydrateRanked(rankedIds: List<BookId>): List<BookListItem> {
        if (rankedIds.isEmpty()) return emptyList()
        val byId =
            bookDao
                .getByIdsWithContributors(rankedIds)
                .associateBy { it.book.id }
                .toMutableMap()
        for (id in rankedIds) {
            if (id !in byId && networkMonitor.isOnline()) {
                fetchAndCacheBook(id)
                bookDao.getByIdWithContributors(id)?.let { byId[id] = it }
            }
        }
        return rankedIds.mapNotNull { byId[it]?.toListItem(imageStorage) }
    }

    /** Local Room FTS5 search — the always-available offline path. */
    private suspend fun searchLocal(query: String): List<BookListItem> {
        val ids =
            searchDao
                .searchBooks(QueryUtils.toFtsQuery(query), limit = SEARCH_LIMIT)
                .map { it.book.id }
        if (ids.isEmpty()) return emptyList()
        val byId = bookDao.getByIdsWithContributors(ids).associateBy { it.book.id }
        return ids.mapNotNull { byId[it]?.toListItem(imageStorage) }
    }

    override suspend fun upsertWithAudioFiles(
        book: BookEntity,
        audioFiles: List<AudioFileEntity>,
    ): AppResult<Unit> =
        suspendRunCatching {
            transactionRunner.atomically {
                bookDao.upsert(book)
                audioFileDao.deleteForBook(book.id.value)
                if (audioFiles.isNotEmpty()) {
                    audioFileDao.upsertAll(audioFiles)
                }
            }
        }

    private companion object {
        /** Cap on book search results — mirrors the server FTS default. */
        const val SEARCH_LIMIT = 50
    }
}

/**
 * Convert DiscoveryBookWithAuthor entity to domain DiscoveryBook.
 */
private fun com.calypsan.listenup.client.data.local.db.DiscoveryBookWithAuthor.toDiscoveryBook(
    imageStorage: ImageStorage,
): DiscoveryBook =
    DiscoveryBook(
        id = id.value,
        title = title,
        authorName = authorName,
        coverPath = imageStorage.coverPathFor(id, coverDownloadedAt),
        coverBlurHash = coverBlurHash,
        coverHash = coverHash,
        createdAt = createdAt.epochMillis,
    )

/**
 * Convert a [DiscoveryBookWithSeries] row to a domain [DiscoveryBook].
 */
private fun com.calypsan.listenup.client.data.local.db.DiscoveryBookWithSeries.toDiscoveryBook(
    imageStorage: ImageStorage,
): DiscoveryBook =
    DiscoveryBook(
        id = id.value,
        title = title,
        authorName = authorName,
        coverPath = imageStorage.coverPathFor(id, coverDownloadedAt),
        coverBlurHash = coverBlurHash,
        coverHash = coverHash,
        createdAt = createdAt.epochMillis,
    )
