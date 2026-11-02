package com.calypsan.listenup.client.playback

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Covers kept decoded; Media3 re-emits the notification on every state tick, so this is read far more than written. */
private const val MAX_ARTWORK_CACHE = 3

/**
 * The notification's cover art: an LRU of decoded covers, filled off the main thread.
 *
 * Media3 builds the notification on the main thread, where decoding a cover on a cache miss stalls the UI on every
 * book change. A miss therefore answers `null` at once and decodes on [decodeDispatcher]; when the cover lands,
 * [load]'s `onDecoded` runs on [scope] (the service's main-thread scope) so the caller can republish.
 *
 * @param scope Where `onDecoded` runs. Cancelling it abandons pending decodes.
 * @param decodeDispatcher Where [decode] runs: somewhere that may block on I/O.
 * @param decode Reads and downsamples the cover at a URI; `null` when it cannot be decoded.
 */
internal class NotificationArtworkCache(
    private val scope: CoroutineScope,
    private val decodeDispatcher: CoroutineDispatcher,
    private val decode: (Uri) -> Bitmap?,
) {
    private val lock = Any()

    private val cache =
        object : LinkedHashMap<String, Bitmap>(MAX_ARTWORK_CACHE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > MAX_ARTWORK_CACHE
        }

    private val inFlight = mutableSetOf<String>()

    /**
     * The decoded cover for [uri], or `null` while it is still being decoded.
     *
     * On a miss, starts the decode (once, however many ticks ask meanwhile) and later calls [onDecoded] with [uri]
     * if it produced a cover. A cover that cannot be decoded calls nothing.
     */
    fun load(
        uri: Uri,
        onDecoded: (Uri) -> Unit,
    ): Bitmap? {
        val key = uri.toString()
        synchronized(lock) {
            cache[key]?.let { return it }
            if (!inFlight.add(key)) return null
        }
        scope.launch {
            val bitmap =
                try {
                    withContext(decodeDispatcher) { decode(uri) }
                } finally {
                    synchronized(lock) { inFlight.remove(key) }
                }
            if (bitmap != null) {
                synchronized(lock) { cache[key] = bitmap }
                onDecoded(uri)
            }
        }
        return null
    }
}
