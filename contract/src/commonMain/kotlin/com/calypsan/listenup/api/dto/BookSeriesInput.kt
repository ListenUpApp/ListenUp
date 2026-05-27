package com.calypsan.listenup.api.dto

import com.calypsan.listenup.core.SeriesId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Input row for [com.calypsan.listenup.api.BookService.setBookSeries].
 *
 * When [id] is non-null, the server uses that series as-is.
 * When [id] is null, the server resolves via `SeriesRepository.resolveOrCreate`.
 */
@Serializable
@SerialName("BookSeriesInput")
data class BookSeriesInput(
    @SerialName("id") val id: SeriesId? = null,
    @SerialName("name") val name: String,
    @SerialName("position") val position: Double? = null,
    @SerialName("isPrimary") val isPrimary: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(name.length <= MAX_NAME) { "name must be <= $MAX_NAME chars" }
    }

    companion object {
        const val MAX_NAME = 500
    }
}
