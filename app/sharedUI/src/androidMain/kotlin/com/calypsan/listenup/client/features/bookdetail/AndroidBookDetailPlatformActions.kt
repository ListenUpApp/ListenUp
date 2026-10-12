package com.calypsan.listenup.client.features.bookdetail

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.download.DownloadManager
import com.calypsan.listenup.client.handoff.ViewedBookTracker
import com.calypsan.listenup.client.playback.PlayerSession
import android.content.Context
import android.content.Intent

/**
 * Android implementation of BookDetailPlatformActions.
 * Delegates to DownloadManager (WorkManager) and the shared [PlayerSession] (Media3 through its engine).
 */
class AndroidBookDetailPlatformActions(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val playerSession: PlayerSession,
    private val viewedBookTracker: ViewedBookTracker,
) : BookDetailPlatformActions {
    override suspend fun downloadBook(bookId: BookId): AppResult<DownloadOutcome> = downloadManager.downloadBook(bookId)

    override suspend fun cancelDownload(bookId: BookId) {
        downloadManager.cancelDownload(bookId)
    }

    override suspend fun deleteDownload(bookId: BookId) {
        downloadManager.deleteDownload(bookId)
    }

    override fun playBook(bookId: BookId) {
        playerSession.playBook(bookId, startAtMs = null)
    }

    override fun playFromChapter(
        bookId: BookId,
        startMs: Long,
    ) {
        playerSession.playBook(bookId, startAtMs = startMs)
    }

    override fun onBookScreenShown(bookId: BookId) {
        viewedBookTracker.onBookShown(bookId)
    }

    override fun onBookScreenHidden(bookId: BookId) {
        viewedBookTracker.onBookHidden(bookId)
    }

    override fun shareText(
        text: String,
        url: String,
    ) {
        val sendIntent =
            Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, text)
                type = "text/plain"
            }
        val shareIntent =
            Intent.createChooser(sendIntent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(shareIntent)
    }
}
