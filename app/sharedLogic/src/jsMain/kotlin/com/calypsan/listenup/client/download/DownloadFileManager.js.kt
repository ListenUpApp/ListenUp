package com.calypsan.listenup.client.download

import kotlinx.io.files.Path

/**
 * Browser download storage.
 *
 * Unimplemented by design (web seam check): offline audio in a browser is its own design — OPFS or
 * the Cache API, plus a quota story — not a port of the filesystem shape these methods describe.
 * Every member throws so the absence is loud; a web client streams only until that design exists.
 */
actual class DownloadFileManager {
    actual fun getAudioFilePath(
        bookId: String,
        audioFileId: String,
        filename: String,
        isTemp: Boolean,
    ): Path = TODO("web: offline download storage is undesigned")

    actual fun deleteBookFiles(bookId: String): Unit = TODO("web: offline download storage is undesigned")

    actual fun deleteAllFiles(): Unit = TODO("web: offline download storage is undesigned")

    actual fun calculateStorageUsed(): Long = TODO("web: offline download storage is undesigned")

    actual fun fileExists(path: String): Boolean = TODO("web: offline download storage is undesigned")

    actual fun getFileSize(path: String): Long = TODO("web: offline download storage is undesigned")

    actual fun moveFile(
        source: Path,
        destination: Path,
    ): Boolean = TODO("web: offline download storage is undesigned")

    actual fun getAvailableSpace(): Long = TODO("web: offline download storage is undesigned")

    actual fun sweepOrphanedTempFiles(activeAudioFileIds: Set<String>): Int =
        TODO("web: offline download storage is undesigned")
}
