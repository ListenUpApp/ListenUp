package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}

private const val FTS_INSERT_CHUNK_SIZE = 200

/**
 * Populates FTS5 tables for offline full-text search.
 *
 * Called after sync operations to ensure FTS tables mirror the main tables.
 * Performs a full rebuild strategy:
 * 1. Clear all FTS entries
 * 2. Re-insert from main tables with denormalized data
 *
 * This approach is simple and correct. For large libraries (>10k books),
 * we could optimize with incremental updates, but full rebuild is fast enough
 * for typical library sizes (<5k books) and avoids complexity of tracking changes.
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
class FtsPopulator(
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

        // Get all contributors
        val contributors = contributorDao.getAll()

        // Insert each contributor
        var insertCount = 0
        for (contributor in contributors) {
            try {
                searchDao.insertContributorFts(
                    contributorId = contributor.id.value,
                    name = contributor.name,
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
