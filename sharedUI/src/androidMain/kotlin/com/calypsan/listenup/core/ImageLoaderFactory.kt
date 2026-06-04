package com.calypsan.listenup.core

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.util.DebugLogger
import okio.Path.Companion.toOkioPath

private const val DISK_CACHE_MAX_SIZE_BYTES = 50L * 1024 * 1024 // 50 MB

/**
 * Factory for creating configured Coil ImageLoader instances.
 *
 * Configures Coil for optimal loading of book cover images:
 * - Loads from local file storage (covers pre-downloaded during sync)
 * - Memory cache for fast repeated access (25% of available memory)
 * - Disk cache for decoded bitmaps (50 MB, separate from source images)
 * - Crossfade animation for smooth UX
 *
 * Note: Server URL fallback for missing covers is handled directly in
 * BookCoverImage via produceState, not through Coil interceptors.
 */
object ImageLoaderFactory {
    fun create(
        context: Context,
        debug: Boolean = false,
    ): ImageLoader =
        ImageLoader
            .Builder(context)
            .memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizePercent(context, percent = 0.25)
                    .build()
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(DISK_CACHE_MAX_SIZE_BYTES)
                    .build()
            }.crossfade(enable = true)
            .apply {
                if (debug) {
                    logger(DebugLogger())
                }
            }.build()
}
