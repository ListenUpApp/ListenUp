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
            // Children/junctions first — several carry a Room foreign key onto the parent
            // table cleared below (library_folders -> libraries; book_contributors,
            // book_series, book_genres, audio_files, book_documents -> books/contributors/
            // series/genres; contributor_aliases -> contributors), so clearing child-first
            // is required, not just tidy. The junctions with no declared FK (book_tags,
            // book_moods, shelf_books, collection_books, collection_shares) are ordered the
            // same way for uniformity. book_readership is a server-fetched cache keyed to
            // books, not a mirrored sync domain, but it is cleared here for the same reason
            // the books access-gate sweeps it: a stale reader list must not survive the reset.
            database.bookContributorDao().deleteAll()
            database.bookSeriesDao().deleteAll()
            database.genreDao().deleteAllBookGenres()
            database.audioFileDao().deleteAll()
            database.bookDocumentDao().deleteAll()
            database.contributorAliasDao().deleteAll()
            database.chapterDao().deleteAll()
            database.bookReadershipDao().deleteAll()
            database.bookTagDao().deleteAll()
            database.bookMoodDao().deleteAll()
            database.shelfBookDao().deleteAll()
            database.collectionBookDao().deleteAll()
            database.collectionShareDao().deleteAll()
            database.playbackPositionDao().deleteAll()
            database.listeningEventDao().deleteAll()
            database.activityDao().deleteAll()
            database.libraryFolderDao().deleteAll()

            // Every mirrored sync domain's root table (SyncDomainCatalog — 21 domains; see
            // LibraryResetHelperTest's drift-proof coverage test for the full accounting).
            database.bookDao().deleteAll()
            database.seriesDao().deleteAll()
            database.contributorDao().deleteAll()
            database.genreDao().deleteAll()
            database.tagDao().deleteAll()
            database.moodDao().deleteAll()
            database.shelfDao().deleteAll()
            database.collectionDao().deleteAll()
            database.libraryDao().deleteAll()
            database.userStatsDao().deleteAll()
            database.publicProfileDao().deleteAll()
            database.adminUserRosterDao().deleteAll()

            // The local FTS5 index mirrors books/contributors/series content, not a domain of
            // its own — clear it alongside its source tables so no stale entry lingers between
            // the reset and the next FtsPopulator rebuild.
            database.searchDao().clearBooksFts()
            database.searchDao().clearContributorsFts()
            database.searchDao().clearSeriesFts()

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
