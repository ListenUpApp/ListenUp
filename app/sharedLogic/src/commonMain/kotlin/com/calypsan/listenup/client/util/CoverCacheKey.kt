package com.calypsan.listenup.client.util

/**
 * Cache key for a book cover image, used as Coil's memory + disk cache key.
 *
 * It folds the cover's content hash into the key so that **replacing a book's cover busts the
 * cache**: the server keeps the same id-based file path on re-cover, and the client unifies the
 * local-file and server-URL requests under one explicit key, so without the hash a changed cover
 * would keep serving the stale (memory- and disk-cached) image — even across an app restart.
 *
 * [coverHash] is the synced content hash of the cover (`book.coverHash`); when it changes the key
 * changes and Coil re-fetches. `null` (no hash yet) falls back to the legacy stable key.
 */
fun bookCoverCacheKey(
    bookId: String,
    coverHash: String?,
): String = "$bookId:${coverHash ?: "cover"}"
