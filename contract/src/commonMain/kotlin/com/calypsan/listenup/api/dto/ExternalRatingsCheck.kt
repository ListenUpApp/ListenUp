package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where Book Detail's on-open Hardcover check stands, streamed by
 * [com.calypsan.listenup.api.BookRatingService.checkExternalRatings]. A client that cannot decode a
 * value a newer server adds sees the stream fail, which it already reads as "not checking".
 */
@Serializable
enum class ExternalRatingsCheck {
    /** A Hardcover fetch for the book is running; its rating, if any, arrives through the ratings sync. */
    @SerialName("CHECKING")
    CHECKING,

    /** The fetch finished, failed, or ran past the server's bound. */
    @SerialName("DONE")
    DONE,
}
