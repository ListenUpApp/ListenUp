package com.calypsan.listenup.client.download

import android.content.Context
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.File

/**
 * Android implementation of DownloadFileManager.
 * Uses Context.filesDir for app-private storage.
 *
 * Uses kotlinx-io for most file operations, with java.io.File only for:
 * - Recursive deletion (no kotlinx-io equivalent)
 * - Storage calculation with walkTopDown (no kotlinx-io equivalent)
 * - Available space query (platform-specific)
 */
actual class DownloadFileManager(
    private val context: Context,
) {
    private val downloadDir: Path
        get() {
            val dir = Path(context.filesDir.absolutePath, "audiobooks")
            // Ensure directory exists using kotlinx-io
            if (!SystemFileSystem.exists(dir)) {
                SystemFileSystem.createDirectories(dir)
            }
            return dir
        }

    actual fun getAudioFilePath(
        bookId: String,
        audioFileId: String,
        filename: String,
        isTemp: Boolean,
    ): Path {
        val bookDir = Path(downloadDir.toString(), bookId)
        // Ensure book directory exists using kotlinx-io
        if (!SystemFileSystem.exists(bookDir)) {
            SystemFileSystem.createDirectories(bookDir)
        }
        val finalName = if (isTemp) "${audioFileId}_$filename.tmp" else "${audioFileId}_$filename"
        return Path(bookDir.toString(), finalName)
    }

    actual fun deleteBookFiles(bookId: String) {
        // java.io.File needed for recursive deletion
        val bookDir = File(downloadDir.toString(), bookId)
        if (bookDir.exists()) {
            bookDir.deleteRecursively()
        }
    }

    actual fun deleteAllFiles() {
        // java.io.File needed for recursive deletion
        val dir = File(downloadDir.toString())
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    actual fun calculateStorageUsed(): Long {
        // java.io.File needed for walkTopDown
        val dir = File(downloadDir.toString())
        return if (dir.exists()) {
            dir
                .walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        } else {
            0L
        }
    }

    actual fun fileExists(path: String): Boolean = SystemFileSystem.exists(Path(path))

    actual fun getFileSize(path: String): Long {
        val filePath = Path(path)
        return SystemFileSystem.metadataOrNull(filePath)?.size ?: 0L
    }

    actual fun moveFile(
        source: Path,
        destination: Path,
    ): Boolean =
        try {
            SystemFileSystem.atomicMove(source, destination)
            true
        } catch (_: Exception) {
            false
        }

    actual fun getAvailableSpace(): Long = context.filesDir.usableSpace

    actual fun sweepOrphanedTempFiles(activeAudioFileIds: Set<String>): Int {
        // java.io.File is used here for walkTopDown — consistent with the existing recursive
        // operations in this actual (deleteBookFiles, deleteAllFiles, calculateStorageUsed).
        val dir = java.io.File(downloadDir.toString())
        if (!dir.exists()) return 0

        var deleted = 0
        dir
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".tmp") }
            .forEach { file ->
                // Filename format: "${audioFileId}_${filename}.tmp". A .tmp is orphaned iff NO active
                // id is a prefix of its name. Matching the FULL id ("${id}_") — not substring-before-
                // first-'_' — keeps ids that legitimately contain '_' from being mis-parsed and their
                // ACTIVE partials wrongly deleted (B10e).
                val isActive = activeAudioFileIds.any { file.name.startsWith("${it}_") }
                if (!isActive && file.delete()) deleted++
            }
        return deleted
    }
}
