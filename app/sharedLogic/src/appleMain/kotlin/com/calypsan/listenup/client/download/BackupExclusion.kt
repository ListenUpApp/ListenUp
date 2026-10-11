package com.calypsan.listenup.client.download

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

/**
 * Marks [path] — and, for a directory, everything inside it — as excluded from iCloud and device
 * backups. Returns whether the mark was applied.
 *
 * For content the server can supply again. Backing it up spends the user's iCloud quota on bytes
 * that are one download away, and Apple reserves backed-up storage for data the user created.
 *
 * The mark belongs to the directory entry: if the directory is deleted and created again it must be
 * applied again.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun excludeFromBackup(path: String): Boolean =
    NSURL.fileURLWithPath(path).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
