package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Curator view of an unmapped scanner genre string aggregated from
 * `pending_book_genres`.
 *
 * - [rawString] — the raw genre string the scanner extracted, untrimmed of case.
 * - [bookCount] — how many distinct books currently carry this pending string.
 * - [firstSeenAt] — earliest `first_seen_at` epoch-millis across those books, so
 *   the curator can prioritise older / more-frequent strings.
 */
@Serializable
@SerialName("UnmappedStringSummary")
data class UnmappedStringSummary(
    @SerialName("rawString") val rawString: String,
    @SerialName("bookCount") val bookCount: Int,
    @SerialName("firstSeenAt") val firstSeenAt: Long,
)
