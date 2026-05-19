package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.domain.repository.LibrarySync
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Contract for resetting library data.
 *
 * Used when detecting library mismatch (server reset) or switching servers.
 */
interface LibraryResetHelperContract {
    /**
     * Clear all library data from local storage.
     *
     * This removes:
     * - All books, series, contributors
     * - All chapters and playback positions
     * - All junction tables (book-series, book-contributor)
     * - All pending sync operations (if discarding local changes)
     * - User data
     * - Sync timestamps
     *
     * Does NOT remove:
     * - Downloaded audio files (managed separately by DownloadDao)
     * - Server connection settings
     * - User preferences (theme, playback speed, etc.)
     *
     * @param discardPendingOperations If true, also clears pending sync operations.
     *        Set to false if you want to preserve unsync'd edits.
     */
    suspend fun clearLibraryData(discardPendingOperations: Boolean = true)

    /**
     * Clear library data and prepare for resync with a new library ID.
     *
     * This is the typical flow when handling a library mismatch:
     * 1. Clear all library data
     * 2. Clear the stored library ID
     * 3. Caller then triggers a fresh sync
     *
     * @param newLibraryId The new library ID to store after clearing
     */
    suspend fun resetForNewLibrary(newLibraryId: String)
}

/**
 * Implementation of library reset helper.
 *
 * Coordinates clearing data across multiple DAOs while preserving
 * non-library data (downloads, preferences).
 *
 * Every delete runs inside a single write transaction — Finding 05 D2 / W4.2 —
 * so a failure at any step leaves the DB untouched rather than partially wiped.
 * Because the reset spans essentially every library table, the helper depends on
 * the [ListenUpDatabase] directly instead of listing each DAO individually; the
 * semantic is "operate on the library as a whole".
 */
class LibraryResetHelper(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    private val librarySyncContract: LibrarySync,
) : LibraryResetHelperContract {
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
