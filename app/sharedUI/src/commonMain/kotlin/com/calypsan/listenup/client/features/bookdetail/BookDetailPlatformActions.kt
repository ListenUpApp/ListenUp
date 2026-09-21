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

    /**
     * Reports that a book screen appeared.
     *
     * ⛔ Here rather than in a shared holder because only Android has anywhere to send it: the
     * platform's Continue On asks the Activity what the reader is in the middle of, and the
     * Activity cannot ask Compose. Desktop no-ops. Routing it through this seam also keeps the
     * handoff types in `androidMain`, which is what keeps them off the iOS Swift Export surface —
     * `:app:sharedLogic`'s commonMain is exported, and three Android-only types were landing there.
     */
    fun onBookScreenShown(bookId: BookId)

    /**
     * Reports that a book screen went away. Takes the id so a screen leaving late cannot clear a
     * newer one's claim — Compose disposes the outgoing screen AFTER the incoming one appears.
     */
    fun onBookScreenHidden(bookId: BookId)

    /** Share text via platform share sheet (Android) or clipboard (Desktop) */
    fun shareText(
        text: String,
        url: String,
    )
}
