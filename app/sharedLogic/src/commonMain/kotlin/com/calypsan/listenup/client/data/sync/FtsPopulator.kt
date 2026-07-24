package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}

private const val FTS_INSERT_CHUNK_SIZE = 200

/** IN-list chunk for id-scoped queries — safely under SQLite's 999-bound-variable limit. */
private const val FTS_ID_CHUNK_SIZE = 500

/** Delta size above which an incremental refresh delegates to a full rebuild. */
private const val FTS_FULL_REBUILD_THRESHOLD = 500

/**
 * Populates FTS5 tables for offline full-text search.
 *
 * Called after sync operations to ensure FTS tables mirror the main tables. Two refresh
 * strategies are available:
 *
 * - [rebuildAll]: clears and repopulates every FTS table from scratch. Used for cold start
 *   ([rebuildIfEmpty]'s self-heal), `forceFullResync` (whose digest repair can rewrite rows at
 *   unchanged revisions, which a watermark comparison would miss), and as [refreshSince]'s own
 *   fallback when a delta is too large to reindex row-by-row.
 * - [refreshSince]: reindexes only the rows changed since a [SearchIndexWatermark] snapshotted
 *   immediately before a sync reconcile — the common case (a scan-completion reconcile touching
 *   a handful of books). Falls back to [rebuildAll] above [FTS_FULL_REBUILD_THRESHOLD] changed
 *   books.
 *
 * The book rebuild issues four aggregate SQL queries (one per denormalized dimension)
 * instead of four per-book queries, then inserts in chunks of [FTS_INSERT_CHUNK_SIZE]
 * rows inside a write transaction per chunk. This removes the O(n) query storm that
 * previously caused heap pressure on large libraries.
 *
 * @property bookDao DAO for reading books
 * @property contributorDao DAO for reading contributors
 * @property seriesDao DAO for reading series
 * @property searchDao DAO for FTS operations
 * @property transactionRunner Wraps chunked FTS inserts in write transactions
 */
internal class FtsPopulator(
    private val bookDao: BookDao,
    private val contributorDao: ContributorDao,
    private val seriesDao: SeriesDao,
    private val searchDao: SearchDao,
    private val transactionRunner: TransactionRunner,
) : FtsPopulatorContract {
    /**
     * Rebuild all FTS tables from main tables.
     *
     * This is a full rebuild that clears and repopulates all FTS tables.
     * Call after sync operations complete to ensure search is up-to-date.
     *
     * Note: FTS tables are created by FtsTableCallback in DatabaseModule on database open,
     * so they will exist by the time this method is called.
     */
    override suspend fun rebuildAll() =
        withContext(IODispatcher) {
            logger.info { "Starting FTS rebuild..." }

            val duration =
                measureTime {
                    val bookCount = rebuildBooks()
                    val contributorCount = rebuildContributors()
                    val seriesCount = rebuildSeries()
                    logger.info {
                        "FTS tables populated: $bookCount books, $contributorCount contributors, $seriesCount series"
                    }
                }

            logger.info { "FTS rebuild completed in ${duration.inWholeMilliseconds}ms" }
        }

    /**
     * Rebuild the index only if it is empty. Detects an install whose library is already in Room but
     * whose FTS index was never populated (e.g. it pre-dates index population) and heals it; a no-op
     * once the index has rows, so it is cheap to call on every engine start.
     */
    override suspend fun rebuildIfEmpty() =
        withContext(IODispatcher) {
            if (searchDao.countBooksFts() == 0) {
                logger.info { "Search index empty — rebuilding from local tables" }
                rebuildAll()
            }
        }

    override suspend fun snapshotWatermark(): SearchIndexWatermark =
        withContext(IODispatcher) {
            SearchIndexWatermark(
                booksRevision = searchDao.maxBookRevision(),
                contributorsRevision = searchDao.maxContributorRevision(),
                seriesRevision = searchDao.maxSeriesRevision(),
                genresRevision = searchDao.maxGenreRevision(),
            )
        }

    override suspend fun refreshSince(watermark: SearchIndexWatermark) =
        withContext(IODispatcher) {
            val changedBookIds =
                buildSet {
                    addAll(searchDao.bookIdsChangedSince(watermark.booksRevision))
                    addAll(searchDao.bookIdsWithContributorsChangedSince(watermark.contributorsRevision))
                    addAll(searchDao.bookIdsWithSeriesChangedSince(watermark.seriesRevision))
                    addAll(searchDao.bookIdsWithGenresChangedSince(watermark.genresRevision))
                }
            val contributorsChanged = searchDao.countContributorsChangedSince(watermark.contributorsRevision) > 0
            val seriesChanged = searchDao.countSeriesChangedSince(watermark.seriesRevision) > 0

            if (changedBookIds.size > FTS_FULL_REBUILD_THRESHOLD) {
                logger.info { "FTS delta of ${changedBookIds.size} books exceeds threshold — full rebuild" }
                rebuildAll()
                return@withContext
            }
            if (changedBookIds.isEmpty() && !contributorsChanged && !seriesChanged) {
                logger.debug { "FTS refresh: nothing changed since watermark — no-op" }
                return@withContext
            }
            val duration =
                measureTime {
                    if (changedBookIds.isNotEmpty()) reindexWithSelfHeal(changedBookIds)
                    if (contributorsChanged) rebuildContributors()
                    if (seriesChanged) rebuildSeries()
                }
            logger.info {
                "Incremental FTS refresh: ${changedBookIds.size} books in ${duration.inWholeMilliseconds}ms"
            }
        }

    override fun observeContentChanges(): Flow<Unit> = searchDao.observeSearchableContentSignal().map { }

    /**
     * Reindex [bookIds], and if any row failed to insert, retry those once, then force a full
     * [rebuildAll] as the last-resort heal — a per-row insert failure must not leave a book MISSING
     * from search forever (FTS-2): its old FTS row was already deleted, and advancing the watermark
     * past it would strand it until an unrelated full rebuild. A transient failure (e.g. a lock)
     * clears on the retry; a genuinely poison book is bounded to what rebuildAll can recover.
     */
    private suspend fun reindexWithSelfHeal(bookIds: Set<String>) {
        val failed = reindexBooks(bookIds)
        if (failed.isEmpty()) return
        logger.warn { "FTS: ${failed.size} book(s) failed to index; retrying once" }
        val stillFailed = reindexBooks(failed)
        if (stillFailed.isNotEmpty()) {
            logger.warn { "FTS: ${stillFailed.size} book(s) failed twice — forcing a full rebuild to self-heal" }
            rebuildAll()
        }
    }

    /**
     * Delete-and-reinsert the books_fts rows for exactly [bookIds]. Tombstoned books in the set get
     * their FTS row deleted and are not re-inserted (the live fetch excludes them). Ids are chunked
     * at [FTS_ID_CHUNK_SIZE] to stay under SQLite's bound-variable limit; each chunk's delete +
     * inserts run in one write transaction so a searcher never sees a half-replaced chunk.
     */
    internal suspend fun reindexBooks(bookIds: Set<String>): Set<String> {
        val failed = mutableSetOf<String>()
        for (idChunk in bookIds.chunked(FTS_ID_CHUNK_SIZE)) {
            val liveBooks = searchDao.getLiveBooksByIds(idChunk)
            val authorsByBookId = searchDao.getPrimaryAuthorNamesFor(idChunk).associate { it.bookId to it.authorName }
            val narratorsByBookId =
                searchDao.getPrimaryNarratorNamesFor(idChunk).associate { it.bookId to it.authorName }
            val seriesByBookId = searchDao.getSeriesNamesGroupedFor(idChunk).associate { it.bookId to it.authorName }
            val genresByBookId = searchDao.getGenreNamesGroupedFor(idChunk).associate { it.bookId to it.authorName }
            transactionRunner.atomically {
                searchDao.deleteBookFtsEntries(idChunk)
                for (book in liveBooks) {
                    try {
                        searchDao.insertBookFts(
                            bookId = book.id.value,
                            title = book.title,
                            subtitle = book.subtitle,
                            description = book.description,
                            author = authorsByBookId[book.id.value],
                            narrator = narratorsByBookId[book.id.value],
                            seriesName = seriesByBookId[book.id.value],
                            genres = genresByBookId[book.id.value],
                        )
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Its old FTS row was deleted above and the re-insert failed — the book is now
                        // MISSING from search. Collect it so the caller can retry / force a rebuild
                        // instead of silently advancing past a permanent index hole (FTS-2).
                        logger.warn(e) { "Failed to reindex book ${book.id} into FTS" }
                        failed += book.id.value
                    }
                }
            }
        }
        return failed
    }

    /**
     * Rebuild book FTS entries.
     *
     * Denormalizes author, narrator, series name, and genre names into the FTS table
     * for rich search results.
     *
     * Uses four aggregate SQL queries (one per dimension) instead of four per-book
     * queries, eliminating the O(n) query storm on large libraries. Each dimension
     * is collected into a map keyed by bookId, then looked up in O(1) per book
     * during the insert pass. Inserts are grouped into chunks of [FTS_INSERT_CHUNK_SIZE]
     * rows, each chunk wrapped in a write transaction to bound lock-hold time.
     *
     * @return Number of books inserted into FTS
     */
    private suspend fun rebuildBooks(): Int {
        logger.debug { "Rebuilding books_fts..." }

        // Clear existing entries
        searchDao.clearBooksFts()

        // Load all books and all four denormalized dimensions in parallel batch queries.
        // O(4) queries total instead of O(4 * n).
        val books = bookDao.getAllLive()
        val authorsByBookId = searchDao.getAllPrimaryAuthorNames().associate { it.bookId to it.authorName }
        val narratorsByBookId = searchDao.getAllPrimaryNarratorNames().associate { it.bookId to it.authorName }
        val seriesByBookId = searchDao.getAllSeriesNamesGrouped().associate { it.bookId to it.authorName }
        val genresByBookId = searchDao.getAllGenreNamesGrouped().associate { it.bookId to it.authorName }

        // Insert in chunks; each chunk is one write transaction to bound lock-hold time.
        var insertCount = 0
        for (chunk in books.chunked(FTS_INSERT_CHUNK_SIZE)) {
            transactionRunner.atomically {
                for (book in chunk) {
                    try {
                        searchDao.insertBookFts(
                            bookId = book.id.value,
                            title = book.title,
                            subtitle = book.subtitle,
                            description = book.description,
                            author = authorsByBookId[book.id.value],
                            narrator = narratorsByBookId[book.id.value],
                            seriesName = seriesByBookId[book.id.value],
                            genres = genresByBookId[book.id.value],
                        )
                        insertCount++
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to insert book ${book.id} into FTS" }
                    }
                }
            }
        }

        logger.debug { "Rebuilt books_fts: $insertCount entries from ${books.size} books" }
        return insertCount
    }

    /**
     * Rebuild contributor FTS entries.
     *
     * @return Number of contributors inserted into FTS
     */
    private suspend fun rebuildContributors(): Int {
        logger.debug { "Rebuilding contributors_fts..." }

        // Clear existing entries
        searchDao.clearContributorsFts()

        // Get all contributors with their aliases so search matches pen names + sort forms.
        val contributors = contributorDao.getAllWithAliases()

        // Insert each contributor
        var insertCount = 0
        for (row in contributors) {
            val contributor = row.contributor
            try {
                searchDao.insertContributorFts(
                    contributorId = contributor.id.value,
                    name = contributor.name,
                    sortName = contributor.sortName,
                    aliases = row.aliases.takeIf { it.isNotEmpty() }?.joinToString(" "),
                    description = contributor.description,
                )
                insertCount++
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to insert contributor ${contributor.id} into FTS" }
            }
        }

        logger.debug { "Rebuilt contributors_fts: $insertCount entries" }
        return insertCount
    }

    /**
     * Rebuild series FTS entries.
     *
     * @return Number of series inserted into FTS
     */
    private suspend fun rebuildSeries(): Int {
        logger.debug { "Rebuilding series_fts..." }

        // Clear existing entries
        searchDao.clearSeriesFts()

        // Get all series
        val series = seriesDao.getAll()

        // Insert each series
        var insertCount = 0
        for (s in series) {
            try {
                searchDao.insertSeriesFts(
                    seriesId = s.id.value,
                    name = s.name,
                    description = s.description,
                )
                insertCount++
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to insert series ${s.id} into FTS" }
            }
        }

        logger.debug { "Rebuilt series_fts: $insertCount entries" }
        return insertCount
    }
}
