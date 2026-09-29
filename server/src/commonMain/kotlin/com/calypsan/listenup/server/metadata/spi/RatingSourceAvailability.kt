package com.calypsan.listenup.server.metadata.spi

import com.calypsan.listenup.api.dto.admin.RatingSourceUnavailable

/**
 * Whether a [RatingSource] can run at all right now. [Unavailable] is a standing condition (no
 * connected account, no credentials) rather than a failure: the fetcher skips the source without
 * recording an error, and it never counts toward the automatic pause.
 */
sealed interface RatingSourceAvailability {
    /** The source can be asked for a rating. */
    data object Available : RatingSourceAvailability

    /** The source cannot run, for [reason]; it is skipped, never penalised. */
    data class Unavailable(
        val reason: RatingSourceUnavailable,
    ) : RatingSourceAvailability
}
