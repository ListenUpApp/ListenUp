package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The offline-first outbox payload for a series lifecycle edit, riding the `series`
 * outbox channel keyed by the series' id.
 *
 * Each variant carries exactly the arguments its backing [com.calypsan.listenup.api.SeriesService]
 * method needs. Both variants are last-write-wins / idempotent, so the channel is safe to re-fire.
 * Merging two series ([com.calypsan.listenup.api.SeriesService.mergeSeries]) stays an online RPC —
 * a merge relinks every membership from source to target server-side and can't be mirrored
 * optimistically. Mirrors [ContributorMutation] / [TagMutation].
 */
@Serializable
sealed interface SeriesMutation {
    /**
     * A metadata PATCH — maps to [com.calypsan.listenup.api.SeriesService.updateSeries].
     *
     * @property patch the per-field PATCH; null fields leave existing state untouched.
     */
    @Serializable
    @SerialName("SeriesMutation.Update")
    data class Update(
        @SerialName("patch") val patch: SeriesUpdate,
    ) : SeriesMutation

    /**
     * Delete the series and cascade-remove its `book_series` memberships — maps to
     * [com.calypsan.listenup.api.SeriesService.deleteSeries].
     */
    @Serializable
    @SerialName("SeriesMutation.Delete")
    data object Delete : SeriesMutation
}
