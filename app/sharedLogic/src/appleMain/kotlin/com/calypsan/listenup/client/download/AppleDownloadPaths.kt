package com.calypsan.listenup.client.download

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/** The directory under `Documents` that holds downloaded audio. */
internal const val AUDIOBOOKS_DIR = "audiobooks"

private const val AUDIOBOOKS_SEGMENT = "/$AUDIOBOOKS_DIR/"

/**
 * The form a finished download's path takes in the database: relative to `Documents`.
 *
 * The app's data container is not at a fixed path — a restore from backup or a device migration
 * moves it — so an absolute path stops resolving while the file is still there.
 */
internal fun storedDownloadPath(absolutePath: String): String {
    val anchor = absolutePath.indexOf(AUDIOBOOKS_SEGMENT)
    return if (anchor < 0) absolutePath else absolutePath.substring(anchor + 1)
}

/**
 * The absolute path of a stored download in the container the app is running in now.
 *
 * Rows written before paths were stored relative hold an absolute path from whatever container
 * existed then; those are re-anchored at their `audiobooks/` segment.
 */
internal fun resolveDownloadPath(
    storedPath: String,
    documentsDir: String,
): String {
    val relative = if (storedPath.startsWith("/")) storedDownloadPath(storedPath) else storedPath
    return if (relative.startsWith("/")) relative else "${documentsDir.trimEnd('/')}/$relative"
}

/** The app's `Documents` directory in the current container. */
internal fun appleDocumentsDirectory(): String =
    (NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).firstOrNull() as? NSURL)
        ?.path
        ?: error("Could not find documents directory")
