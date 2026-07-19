package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Aggregate stats for one facet (genre/tag/mood) over the live library: how many books carry it and
 * their combined listening length. Library-wide (NOT per-viewer access-filtered) — the browse list
 * is access-filtered separately. [totalDurationMs] is the sum of `total_duration` across those books.
 */
@Serializable
@SerialName("FacetStats")
data class FacetStats(
    @SerialName("bookCount") val bookCount: Int,
    @SerialName("totalDurationMs") val totalDurationMs: Long,
) {
    companion object {
        val EMPTY = FacetStats(bookCount = 0, totalDurationMs = 0L)
    }
}
