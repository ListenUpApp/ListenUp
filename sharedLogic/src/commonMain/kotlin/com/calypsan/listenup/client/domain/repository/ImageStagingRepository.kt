package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId

/**
 * Domain repository owning the staging-location lifecycle for book and series cover art.
 *
 * Supports the preview-before-save workflow: images are written to a temporary staging
 * path so the UI can display them before the user commits the edit. The final commit
 * (or cleanup on cancel/clear) is also coordinated here.
 *
 * Implementations live in the data layer; use cases and ViewModels depend only on this
 * interface.
 */
interface ImageStagingRepository {
    // ========== Book Cover Staging ==========

    /**
     * Save book cover to staging location for preview.
     *
     * Used during book editing to preview cover changes before committing.
     *
     * @param bookId Unique identifier for the book
     * @param imageData Raw image bytes
     * @return Result indicating success or failure
     */
    suspend fun saveBookCoverStaging(
        bookId: BookId,
        imageData: ByteArray,
    ): AppResult<Unit>

    /**
     * Get the local file path for a book's staging cover.
     *
     * @param bookId Unique identifier for the book
     * @return Absolute file path where the staging cover is stored
     */
    fun getBookCoverStagingPath(bookId: BookId): String

    /**
     * Delete staging cover file.
     *
     * Used when canceling edits or cleaning up.
     *
     * @param bookId Unique identifier for the book
     * @return Result indicating success or failure
     */
    suspend fun deleteBookCoverStaging(bookId: BookId): AppResult<Unit>

    /**
     * Commit staged book cover to the main location.
     *
     * Used when saving book edits - moves the staged cover to the main cover path.
     *
     * @param bookId Unique identifier for the book
     * @return Result indicating success or failure
     */
    suspend fun commitBookCoverStaging(bookId: BookId): AppResult<Unit>

    /**
     * Request fire-and-forget cleanup of any staging cover file for this book.
     *
     * Runs on an application-scoped CoroutineScope so the caller may invoke this
     * from `ViewModel.onCleared()` safely. Errors are logged but not surfaced —
     * a stale staging file is benign.
     */
    fun requestBookCoverStagingCleanup(bookId: BookId)

    // ========== Series Cover Staging ==========

    /**
     * Save series cover to staging location for preview.
     *
     * Used during series editing to preview cover changes before committing.
     *
     * @param seriesId Unique identifier for the series
     * @param imageData Raw image bytes
     * @return Result indicating success or failure
     */
    suspend fun saveSeriesCoverStaging(
        seriesId: String,
        imageData: ByteArray,
    ): AppResult<Unit>

    /**
     * Get the local file path for a series's staging cover.
     *
     * @param seriesId Unique identifier for the series
     * @return Absolute file path where the staging cover is stored
     */
    fun getSeriesCoverStagingPath(seriesId: String): String

    /**
     * Delete series staging cover file.
     *
     * Used when canceling edits or cleaning up.
     *
     * @param seriesId Unique identifier for the series
     * @return Result indicating success or failure
     */
    suspend fun deleteSeriesCoverStaging(seriesId: String): AppResult<Unit>

    /**
     * Commit staged series cover to the main location.
     *
     * @param seriesId Unique identifier for the series
     * @return Result indicating success or failure
     */
    suspend fun commitSeriesCoverStaging(seriesId: String): AppResult<Unit>

    /**
     * Request fire-and-forget cleanup of any staging cover file for this series.
     *
     * Same semantics as [requestBookCoverStagingCleanup].
     */
    fun requestSeriesCoverStagingCleanup(seriesId: String)
}
