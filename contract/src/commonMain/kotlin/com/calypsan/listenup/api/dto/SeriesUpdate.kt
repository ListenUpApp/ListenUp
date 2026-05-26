package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PATCH payload for [com.calypsan.listenup.api.SeriesService.updateSeries].
 *
 * Every field is nullable — `null` means "don't touch."
 */
@Serializable
@SerialName("SeriesUpdate")
data class SeriesUpdate(
    @SerialName("name") val name: String? = null,
    @SerialName("sortName") val sortName: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("coverPath") val coverPath: String? = null,
    @SerialName("asin") val asin: String? = null,
) {
    init {
        name?.let { require(it.isNotBlank() && it.length <= MAX_NAME) { "name must be 1..$MAX_NAME chars" } }
        sortName?.let { require(it.length <= MAX_NAME) { "sortName must be <= $MAX_NAME chars" } }
        description?.let { require(it.length <= MAX_DESCRIPTION) { "description must be <= $MAX_DESCRIPTION chars" } }
        coverPath?.let { require(it.length <= MAX_PATH) { "coverPath must be <= $MAX_PATH chars" } }
        asin?.let { require(it.length <= MAX_ASIN) { "asin must be <= $MAX_ASIN chars" } }
    }

    companion object {
        const val MAX_NAME = 500
        const val MAX_DESCRIPTION = 10_000
        const val MAX_PATH = 1024
        const val MAX_ASIN = 20
    }
}
