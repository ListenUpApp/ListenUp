package com.calypsan.listenup.client.features.bookdetail

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingViewModel

/**
 * Desktop implementation of BookDetailPlatformActions.
 *
 * Playback is available via JavaFX MediaPlayer when GStreamer is installed. Downloads
 * are not yet implemented on desktop, so download-related methods return stubs.
 */
class DesktopBookDetailPlatformActions(
    private val nowPlayingViewModel: NowPlayingViewModel,
) : BookDetailPlatformActions {
    override suspend fun downloadBook(bookId: BookId): AppResult<DownloadOutcome> =
        AppResult.Failure(DownloadError.DownloadFailed(debugInfo = "Downloads not yet available on desktop"))

    override suspend fun cancelDownload(bookId: BookId) {}

    override suspend fun deleteDownload(bookId: BookId) {}

    override fun playBook(bookId: BookId) {
        nowPlayingViewModel.playBook(bookId)
    }

    override fun shareText(
        text: String,
        url: String,
    ) {
        val selection = java.awt.datatransfer.StringSelection(text)
        java.awt.Toolkit
            .getDefaultToolkit()
            .systemClipboard
            .setContents(selection, null)
    }
}
