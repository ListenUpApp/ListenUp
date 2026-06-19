package com.calypsan.listenup.server.metadata.itunes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Raw iTunes Search API response. Cover-only scope — we do not use trackCount,
 * releaseDate, price, or genre fields because Audible is the authoritative metadata
 * source. Only the artwork URL and minimal identification fields are kept.
 */
@Serializable
internal data class ITunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ITunesSearchResult> = emptyList(),
)

/**
 * A single match from the iTunes Search API.
 *
 * The [wrapperType] and [collectionType] fields are used to filter to real
 * audiobook entries (Go's SearchAudiobooks filters out non-audiobook wrapper
 * types). [artworkUrl100] is transformed to the maximum-resolution variant by
 * [ITunesClient.toCoverHit]; [artworkUrl60] is used as a fallback if 100px is
 * absent.
 */
@Serializable
internal data class ITunesSearchResult(
    @SerialName("wrapperType") val wrapperType: String? = null,
    @SerialName("collectionType") val collectionType: String? = null,
    @SerialName("collectionId") val collectionId: Long? = null,
    @SerialName("collectionName") val collectionName: String? = null,
    @SerialName("artistName") val artistName: String? = null,
    /** iTunes returns artwork at 100×100 by default. */
    @SerialName("artworkUrl100") val artworkUrl100: String? = null,
    /** Fallback artwork at 60×60 when 100px URL is absent. */
    @SerialName("artworkUrl60") val artworkUrl60: String? = null,
)

/**
 * The cover hit returned to callers.
 *
 * [coverUrl] is the original 100×100 thumbnail URL from iTunes (kept for
 * reference and low-bandwidth contexts). [maxSizeUrl] is the high-resolution
 * variant — typically up to 7000×7000, with iTunes serving the actual maximum
 * available size — derived by URL substitution per Go's cover.go pattern.
 */
data class ITunesCoverHit(
    val coverUrl: String,
    val maxSizeUrl: String,
    val sourceId: String = "",
)
