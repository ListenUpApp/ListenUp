package com.calypsan.listenup.client.features.bookdetail

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.DownloadOutcome

/**
 * Platform-specific side-effect actions for the Book Detail screen.
 *
 * State (download status, reachability, wifi-only) is owned by BookDetailViewModel
 * and delivered through BookDetailUiState.Ready. This interface covers only the
 * imperative side-effects that require platform APIs.
 *
 * Android: Provides full download management and playback via WorkManager + Media3
 * Desktop: No-op or minimal implementation (downloads not yet available)
 */
interface BookDetailPlatformActions {
    /** Start downloading a book */
    suspend fun downloadBook(bookId: BookId): AppResult<DownloadOutcome>

    /** Cancel an in-progress download */
    suspend fun cancelDownload(bookId: BookId)

    /** Delete downloaded files for a book */
    suspend fun deleteDownload(bookId: BookId)

    /** Start playback for a book */
    fun playBook(bookId: BookId)

    /** Share text via platform share sheet (Android) or clipboard (Desktop) */
    fun shareText(
        text: String,
        url: String,
    )
}
