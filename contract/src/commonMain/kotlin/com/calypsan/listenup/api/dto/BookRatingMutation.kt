package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The outbox payload for a listener's rating. Both variants are the listener's whole terminal
 * state for [bookId], so the outbox coalesces them: three taps offline send one operation.
 */
@Serializable
sealed interface BookRatingMutation {
    /** The book this mutation is about. */
    val bookId: String

    /** Rate [bookId] — maps to [com.calypsan.listenup.api.BookRatingService.rate]. */
    @Serializable
    @SerialName("BookRatingMutation.Set")
    data class Set(
        @SerialName("bookId") override val bookId: String,
        @SerialName("candidateId") val candidateId: String,
        @SerialName("halfStars") val halfStars: Int,
        @SerialName("note") val note: String?,
    ) : BookRatingMutation

    /** Remove the listener's rating of [bookId] — maps to [com.calypsan.listenup.api.BookRatingService.clearRating]. */
    @Serializable
    @SerialName("BookRatingMutation.Clear")
    data class Clear(
        @SerialName("bookId") override val bookId: String,
    ) : BookRatingMutation
}
