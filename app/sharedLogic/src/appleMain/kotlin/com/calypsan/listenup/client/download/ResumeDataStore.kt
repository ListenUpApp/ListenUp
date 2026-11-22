@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.client.download

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

private const val RESUME_DIRECTORY = "download-resume"
private const val EXTENSION = "resume"
private const val NO_EXPIRY = "none"

/** Resume data for one file, and when the signed URL it resumes stops working (epoch seconds). */
internal class ResumeBlob(
    val data: NSData,
    val urlExpiresAt: Long?,
)

/**
 * Resume data for interrupted downloads, one file per audio file:
 * `<audioFileId>.<urlExpiresAt>.resume` under `Library/Application Support/download-resume`.
 *
 * Not in Documents, which is for the person's content, and not in Room: it is an opaque blob with a
 * life of its own, and it is useless on any other device, so the directory is kept out of backups.
 * The URL's expiry rides in the file name because the blob itself is an undocumented format; it
 * lets a resume whose URL has died skip straight to asking the server for a new one.
 */
internal class ResumeDataStore(
    private val directory: String,
) {
    /** Store [data] for [audioFileId], replacing any earlier resume data. Returns whether it was written. */
    fun save(
        audioFileId: String,
        data: NSData,
        urlExpiresAt: Long?,
    ): Boolean {
        if (!ensureDirectory()) return false
        delete(audioFileId)
        return data.writeToFile(path(audioFileId, urlExpiresAt), options = NSDataWritingAtomic, error = null)
    }

    /** The resume data stored for [audioFileId], or null. */
    fun load(audioFileId: String): ResumeBlob? {
        val (name, expiry) = entries()[audioFileId] ?: return null
        val data = NSData.dataWithContentsOfFile("$directory/$name") ?: return null
        return ResumeBlob(data, expiry)
    }

    /** Every file with resume data, and the expiry of the URL each resumes. */
    fun storedFiles(): Map<String, Long?> = entries().mapValues { (_, entry) -> entry.second }

    fun delete(audioFileId: String) {
        entries()[audioFileId]?.let { (name, _) ->
            NSFileManager.defaultManager.removeItemAtPath("$directory/$name", error = null)
        }
    }

    fun deleteAll() {
        NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
    }

    /** File name and URL expiry per audio file id, read from the directory listing. */
    private fun entries(): Map<String, Pair<String, Long?>> =
        NSFileManager.defaultManager
            .contentsOfDirectoryAtPath(directory, error = null)
            .orEmpty()
            .filterIsInstance<String>()
            .mapNotNull { name ->
                val parts = name.split(".")
                if (parts.size < 3 || parts.last() != EXTENSION) return@mapNotNull null
                val id = parts.dropLast(2).joinToString(".")
                id to (name to parts[parts.size - 2].toLongOrNull())
            }.toMap()

    private fun path(
        audioFileId: String,
        urlExpiresAt: Long?,
    ): String = "$directory/${audioFileId.replace("/", "_")}.${urlExpiresAt ?: NO_EXPIRY}.$EXTENSION"

    private fun ensureDirectory(): Boolean {
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(directory)) {
            val created =
                fileManager.createDirectoryAtPath(
                    directory,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                )
            if (!created) return false
        }
        // The mark belongs to the directory entry, so it is applied every time rather than once.
        excludeFromBackup(directory)
        return true
    }
}

/** `Library/Application Support/download-resume` in the current container. */
internal fun appleResumeDataDirectory(): String {
    val support =
        (
            NSFileManager.defaultManager
                .URLsForDirectory(
                    NSApplicationSupportDirectory,
                    NSUserDomainMask,
                ).firstOrNull() as? NSURL
        )?.path
            ?: error("Could not find the Application Support directory")
    return "$support/$RESUME_DIRECTORY"
}

/** The `exp` (epoch seconds) of a signed audio URL, or null when it carries none. */
internal fun signedUrlExpiry(url: String?): Long? =
    url
        ?.substringAfter('?', missingDelimiterValue = "")
        ?.split('&')
        ?.firstOrNull { it.startsWith("exp=") }
        ?.removePrefix("exp=")
        ?.toLongOrNull()
