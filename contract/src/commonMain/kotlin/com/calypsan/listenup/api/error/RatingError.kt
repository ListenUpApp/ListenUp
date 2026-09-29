package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Outside rating-source failures ([com.calypsan.listenup.api.BookRatingService]). */
@Serializable
sealed interface RatingError : AppError {
    /**
     * Every enabled outside rating source failed to answer for this refresh (applying a match, the
     * nightly sweep, or an admin's explicit refresh request). Safe to retry after a delay.
     */
    @Serializable
    @SerialName("RatingError.SourceUnavailable")
    data class SourceUnavailable(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : RatingError {
        override val message: String = "The rating sources couldn't be reached. Try again later."
        override val code: String = "RATING_SOURCE_UNAVAILABLE"
        override val isRetryable: Boolean = true
    }
}
