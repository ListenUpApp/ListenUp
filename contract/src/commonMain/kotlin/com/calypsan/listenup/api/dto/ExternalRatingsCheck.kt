package com.calypsan.listenup.api.dto

import com.calypsan.listenup.api.LenientEnumSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where Book Detail's on-open Hardcover check stands, streamed by
 * [com.calypsan.listenup.api.BookRatingService.checkExternalRatings].
 *
 * Serialized by [ExternalRatingsCheckSerializer]: it is the stream's element, with no property to
 * default, so a value a newer server adds reads as [UNKNOWN] instead of failing the stream.
 */
@Serializable(with = ExternalRatingsCheckSerializer::class)
enum class ExternalRatingsCheck {
    /** A Hardcover fetch for the book is running; its rating, if any, arrives through the ratings sync. */
    @SerialName("CHECKING")
    CHECKING,

    /** The fetch finished, failed, or ran past the server's bound. */
    @SerialName("DONE")
    DONE,

    /**
     * What an older app reads a value a newer server added as. Clients treat it as still checking:
     * the stream's completion, not this value, is what ends the wait. The server never sends it.
     */
    @SerialName("UNKNOWN")
    UNKNOWN,
}

/** Decodes [ExternalRatingsCheck] by name, falling back to [ExternalRatingsCheck.UNKNOWN] for one this build has never heard of. */
object ExternalRatingsCheckSerializer :
    KSerializer<ExternalRatingsCheck> by LenientEnumSerializer(
        "ExternalRatingsCheck",
        ExternalRatingsCheck.entries,
        ExternalRatingsCheck.UNKNOWN,
    )
