package com.calypsan.listenup.client.data.local.images

import kotlinx.io.files.Path

/**
 * Platform-specific storage paths for image files.
 * Each platform provides its own implementation for app-private storage locations.
 */
interface StoragePaths {
    /**
     * Base directory for app-private files (app's internal storage).
     * On Android: context.filesDir
     * On iOS: NSDocumentDirectory
     * On Desktop: platform-specific app data directory
     */
    val filesDir: Path
}
