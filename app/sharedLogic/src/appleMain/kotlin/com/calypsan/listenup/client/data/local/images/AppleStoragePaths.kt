package com.calypsan.listenup.client.data.local.images

import kotlinx.io.files.Path
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * iOS implementation of [StoragePaths] using app's Document directory.
 */
class AppleStoragePaths : StoragePaths {
    override val filesDir: Path by lazy {
        val fileManager = NSFileManager.defaultManager
        val urls = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
        val documentUrl = urls.firstOrNull() ?: error("Could not find Documents directory")

        @Suppress("CAST_NEVER_SUCCEEDS")
        val path =
            (documentUrl as platform.Foundation.NSURL).path
                ?: error("Could not get path from Documents URL")
        Path(path)
    }
}
