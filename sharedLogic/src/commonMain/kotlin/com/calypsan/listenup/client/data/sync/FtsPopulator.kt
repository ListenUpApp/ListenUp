package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesDao
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}

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
 * @property bookDao DAO for reading books
 * @property contributorDao DAO for reading contributors
 * @property seriesDao DAO for reading series
 * @property searchDao DAO for FTS operations
 */
class FtsPopulator(
    private val bookDao: BookDao,
    private val contributorDao: ContributorDao,
    private val seriesDao: SeriesDao,
    private val searchDao: SearchDao,
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
     * Rebuild book FTS entries.
     *
     * Denormalizes author, narrator, and series name into the FTS table
     * for rich search results.
     *
     * @return Number of books inserted into FTS
     */
    private suspend fun rebuildBooks(): Int {
        logger.debug { "Rebuilding books_fts..." }

        // Clear existing entries
        searchDao.clearBooksFts()

        // Get all books
        val books = bookDao.getAll()

        // Insert each book with denormalized data
        var insertCount = 0
        for (book in books) {
            try {
                // Get denormalized data for FTS indexing
                val authorName = searchDao.getPrimaryAuthorName(book.id.value)
                val narratorName = searchDao.getPrimaryNarratorName(book.id.value)
                val seriesNames = searchDao.getSeriesNamesForBook(book.id.value)
                val genreNames = searchDao.getGenreNamesForBook(book.id.value)

                searchDao.insertBookFts(
                    bookId = book.id.value,
                    title = book.title,
                    subtitle = book.subtitle,
                    description = book.description,
                    author = authorName,
                    narrator = narratorName,
                    seriesName = seriesNames,
                    genres = genreNames,
                )
                insertCount++
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to insert book ${book.id} into FTS" }
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
