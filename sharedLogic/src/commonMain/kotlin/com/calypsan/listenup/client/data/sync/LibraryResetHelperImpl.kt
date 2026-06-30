package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.domain.repository.LibraryResetHelper
import com.calypsan.listenup.client.domain.repository.LibrarySync
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Implementation of library reset helper.
 *
 * Coordinates clearing data across multiple DAOs while preserving
 * non-library data (downloads, preferences).
 *
 * Every delete runs inside a single write transaction, so a failure at any step
 * leaves the DB untouched rather than partially wiped.
 * Because the reset spans essentially every library table, the helper depends on
 * the [ListenUpDatabase] directly instead of listing each DAO individually; the
 * semantic is "operate on the library as a whole".
 */
internal class LibraryResetHelperImpl(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    private val librarySyncContract: LibrarySync,
) : LibraryResetHelper {
    override suspend fun clearLibraryData(discardPendingOperations: Boolean) {
        logger.info { "Clearing library data (discardPendingOperations=$discardPendingOperations)" }

        transactionRunner.atomically {
            database.bookContributorDao().deleteAll()
            database.bookSeriesDao().deleteAll()
            database.chapterDao().deleteAll()
            database.playbackPositionDao().deleteAll()
            database.bookDao().deleteAll()
            database.seriesDao().deleteAll()
            database.contributorDao().deleteAll()
            database.userDao().clear()

            // Sync cursors share the library's lifecycle: if the rows are wiped
            // but the cursors survive, the next login's catch-up resumes from the
            // stale high-water cursor and never re-pulls the unchanged library,
            // leaving it permanently empty. Reset them so catch-up starts fresh.
            database.syncCursorDao().deleteAll()

            if (discardPendingOperations) {
                database.pendingOperationV2Dao().deleteAll()
            }
        }

        logger.info { "Library data cleared successfully" }
    }

    override suspend fun resetForNewLibrary(newLibraryId: String) {
        logger.info { "Resetting for new library: $newLibraryId" }

        // Clear all library data including pending operations
        clearLibraryData(discardPendingOperations = true)

        // Store the new library ID
        librarySyncContract.setConnectedLibraryId(newLibraryId)

        logger.info { "Ready for sync with new library" }
    }
}
