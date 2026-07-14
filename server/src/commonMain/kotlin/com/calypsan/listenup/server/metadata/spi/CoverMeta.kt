package com.calypsan.listenup.server.metadata.spi

/**
 * One cover-art candidate from a [CoverSource] search.
 *
 * [url] is the directly usable image; [maxSizeUrl] points at the largest available
 * rendition when the catalog exposes a distinct high-res URL (often the same host
 * with a size token swapped). [sourceKey] identifies which catalog entry the cover
 * came from so a later step can attribute or de-duplicate it.
 */
data class CoverMeta(
    /** Directly usable cover image URL. */
    val url: String,
    /** Largest available rendition URL, when distinct from [url]. */
    val maxSizeUrl: String? = null,
    /** The catalog entry key this cover belongs to. */
    val sourceKey: String,
)
