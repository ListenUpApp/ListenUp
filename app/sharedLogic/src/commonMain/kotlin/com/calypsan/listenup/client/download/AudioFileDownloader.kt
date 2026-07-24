package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.result.AppResult

/**
 * Domain seam for downloading a single audiobook audio file to local storage.
 *
 * Owns the HTTP transport entirely inside `:app:sharedLogic`: the implementation resolves the signed
 * download URL, streams the bytes (with Range-resume), updates the
 * [com.calypsan.listenup.client.domain.repository.DownloadRepository], and moves the completed file
 * into place. Consumers — notably the Android `DownloadWorker` — depend on this interface rather
 * than on a raw Ktor `HttpClient`, so no transport type crosses a module boundary (which would drag
 * the untranslatable Ktor client bridge onto the Swift Export surface).
 *
 * The signature speaks only domain/stdlib types; cancellation and progress are surfaced through the
 * [isStopped] and [setProgress] callbacks so the worker can bridge them to WorkManager.
 */
interface AudioFileDownloader {
    /**
     * Download the audio file identified by [audioFileId] (belonging to [bookId]) to its local
     * destination under [filename].
     *
     * @param expectedSize the expected file size in bytes for integrity verification, or `0` if unknown.
     * @param isStopped polled during streaming; returning `true` cancels the download (the cancellation
     *   propagates as [kotlinx.coroutines.CancellationException] so callers can distinguish it from failure).
     * @param setProgress invoked with `(downloadedBytes, totalBytes)` as the download advances.
     * @return [AppResult.Success] when fully downloaded, [AppResult.Failure] with a typed
     *   [com.calypsan.listenup.api.error.AppError] on any failure.
     */
    suspend fun download(
        audioFileId: String,
        bookId: String,
        filename: String,
        expectedSize: Long,
        isStopped: () -> Boolean = { false },
        setProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): AppResult<Unit>
}
