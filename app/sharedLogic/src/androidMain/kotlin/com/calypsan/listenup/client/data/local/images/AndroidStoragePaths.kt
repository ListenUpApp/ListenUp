package com.calypsan.listenup.client.data.local.images

import android.content.Context
import kotlinx.io.files.Path

/**
 * Android implementation of [StoragePaths] using app-private storage.
 */
class AndroidStoragePaths(
    context: Context,
) : StoragePaths {
    override val filesDir: Path = Path(context.filesDir.absolutePath)
}
