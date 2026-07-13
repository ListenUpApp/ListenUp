package com.calypsan.listenup.client.download

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * iOS implementation of DownloadFileManager.
 * Uses NSDocumentDirectory for persistent storage that survives app updates.
 */
@OptIn(ExperimentalForeignApi::class)
actual class DownloadFileManager {
    private val fileManager = NSFileManager.defaultManager

    private val downloadDir: Path
        get() {
            val documentsUrl =
                fileManager
                    .URLsForDirectory(
                        NSDocumentDirectory,
                        NSUserDomainMask,
                    ).firstOrNull() as? NSURL
                    ?: error("Could not find documents directory")

            val audiobooksUrl = documentsUrl.URLByAppendingPathComponent("audiobooks")!!
            val path = Path(audiobooksUrl.path!!)

            // Ensure directory exists
            if (!fileManager.fileExistsAtPath(audiobooksUrl.path!!)) {
                fileManager.createDirectoryAtURL(
                    audiobooksUrl,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                )
            }
            return path
        }

    actual fun getAudioFilePath(
        bookId: String,
        audioFileId: String,
        filename: String,
        isTemp: Boolean,
    ): Path {
        val bookDirPath = Path(downloadDir, bookId)

        // Ensure book directory exists
        if (!fileManager.fileExistsAtPath(bookDirPath.toString())) {
            fileManager.createDirectoryAtPath(
                bookDirPath.toString(),
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        }
        val finalName = if (isTemp) "${audioFileId}_$filename.tmp" else "${audioFileId}_$filename"
        return Path(bookDirPath, finalName)
    }

    actual fun deleteBookFiles(bookId: String) {
        val bookDirPath = Path(downloadDir, bookId).toString()
        if (fileManager.fileExistsAtPath(bookDirPath)) {
            fileManager.removeItemAtPath(bookDirPath, error = null)
        }
    }

    actual fun deleteAllFiles() {
        val dirPath = downloadDir.toString()
        if (fileManager.fileExistsAtPath(dirPath)) {
            fileManager.removeItemAtPath(dirPath, error = null)
        }
    }

    actual fun calculateStorageUsed(): Long {
        val dirPath = downloadDir.toString()
        if (!fileManager.fileExistsAtPath(dirPath)) return 0L

        var totalSize = 0L
        val enumerator = fileManager.enumeratorAtPath(dirPath) ?: return 0L

        while (true) {
            val file = enumerator.nextObject() as? String ?: break
            val filePath = Path(downloadDir, file).toString()
            val attrs = fileManager.attributesOfItemAtPath(filePath, error = null)
            val size = attrs?.get(NSFileSize) as? Long ?: 0L
            totalSize += size
        }
        return totalSize
    }

    actual fun fileExists(path: String): Boolean = fileManager.fileExistsAtPath(path)

    actual fun getFileSize(path: String): Long {
        if (!fileManager.fileExistsAtPath(path)) return 0L
        val attrs = fileManager.attributesOfItemAtPath(path, error = null)
        return attrs?.get(NSFileSize) as? Long ?: 0L
    }

    actual fun moveFile(
        source: Path,
        destination: Path,
    ): Boolean =
        fileManager.moveItemAtPath(
            source.toString(),
            destination.toString(),
            error = null,
        )

    actual fun getAvailableSpace(): Long {
        val documentsUrl =
            fileManager
                .URLsForDirectory(
                    NSDocumentDirectory,
                    NSUserDomainMask,
                ).firstOrNull() as? NSURL ?: return 0L

        val attrs = fileManager.attributesOfFileSystemForPath(documentsUrl.path!!, error = null)
        return attrs?.get(NSFileSystemFreeSize) as? Long ?: 0L
    }

    actual fun sweepOrphanedTempFiles(activeAudioFileIds: Set<String>): Int {
        val dirPath = downloadDir.toString()
        if (!fileManager.fileExistsAtPath(dirPath)) return 0

        val enumerator = fileManager.enumeratorAtPath(dirPath) ?: return 0
        var deleted = 0

        while (true) {
            val relativePath = enumerator.nextObject() as? String ?: break
            if (!relativePath.endsWith(".tmp")) continue

            // relativePath is relative to dirPath; extract just the filename component.
            val filename = relativePath.substringAfterLast('/')
            // Filename format: "${audioFileId}_${filename}.tmp". A .tmp is orphaned iff NO active id
            // is a prefix of its name. Matching the FULL id ("${id}_") — not substring-before-first-
            // '_' — keeps ids that legitimately contain '_' from being mis-parsed and their ACTIVE
            // partials wrongly deleted (B10e).
            val isActive = activeAudioFileIds.any { filename.startsWith("${it}_") }
            if (!isActive) {
                val fullPath = Path(downloadDir, relativePath).toString()
                if (fileManager.removeItemAtPath(fullPath, error = null)) deleted++
            }
        }
        return deleted
    }
}
