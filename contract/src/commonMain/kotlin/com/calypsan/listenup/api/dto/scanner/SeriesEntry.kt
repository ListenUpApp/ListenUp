package com.calypsan.listenup.api.dto.scanner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A book's membership in a series. `sequence` is intentionally a string —
 * ABS treats `"1.5"` and `"0a"` as valid (matches `BookSeries.sequence` in
 * the ABS schema). Numeric parsing is the consumer's job, never ours.
 */
@Serializable
data class SeriesEntry(
    @SerialName("name")
    val name: String,
    val sequence: String? = null,
)
