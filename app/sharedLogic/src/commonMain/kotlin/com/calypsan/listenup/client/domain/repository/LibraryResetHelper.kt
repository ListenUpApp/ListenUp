package com.calypsan.listenup.client.domain.repository

/**
 * Contract for resetting library data.
 *
 * Used when detecting library mismatch (server reset) or switching servers.
 */
interface LibraryResetHelper {
    /**
     * Clear all library data from local storage.
     *
     * This removes:
     * - All books, series, contributors
     * - All chapters and playback positions
     * - All junction tables (book-series, book-contributor)
     * - All pending sync operations (if discarding local changes)
     * - User data
     * - Per-domain sync cursors (so catch-up re-pulls the library on next login)
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
