package com.calypsan.listenup.api.dto

import com.calypsan.listenup.domain.ListenerRatingLimits
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A listener rating a book. Validated here, at the boundary, so the service can trust it:
 * [halfStars] must be 2..10 and [note] at most [ListenerRatingLimits.NOTE_MAX_CHARS] once trimmed.
 * [note] travels as typed; whoever stores it applies [ListenerRatingLimits.normalizeNote].
 * [candidateId] is the client-minted wire id the server uses only when no row exists yet for the
 * pair, so the optimistic local row and the server row share one id. It must not be blank — an
 * empty id is not a usable candidate, only a UUID-shaped id is expected in practice, but no
 * particular shape is enforced here beyond non-blank.
 */
@Serializable
@SerialName("RateBookRequest")
data class RateBookRequest(
    @SerialName("candidateId") val candidateId: String,
    @SerialName("halfStars") val halfStars: Int,
    @SerialName("note") val note: String?,
) {
    init {
        require(candidateId.isNotBlank()) { "candidateId must not be blank" }
        require(halfStars in ListenerRatingLimits.MIN_HALF_STARS..ListenerRatingLimits.MAX_HALF_STARS) {
            "halfStars must be ${ListenerRatingLimits.MIN_HALF_STARS}..${ListenerRatingLimits.MAX_HALF_STARS}"
        }
        require((ListenerRatingLimits.normalizeNote(note)?.length ?: 0) <= ListenerRatingLimits.NOTE_MAX_CHARS) {
            "note exceeds ${ListenerRatingLimits.NOTE_MAX_CHARS} characters"
        }
    }
}
