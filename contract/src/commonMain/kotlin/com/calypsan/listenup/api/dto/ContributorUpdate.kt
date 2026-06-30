package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PATCH payload for [com.calypsan.listenup.api.ContributorService.updateContributor].
 *
 * Every field is nullable — `null` means "don't touch." Aliases are **not**
 * present because contributor aliases are not yet a server-side concept; a
 * future `contributor_aliases` server substrate will introduce them.
 */
@Serializable
@SerialName("ContributorUpdate")
data class ContributorUpdate(
    @SerialName("name") val name: String? = null,
    @SerialName("sortName") val sortName: String? = null,
    @SerialName("asin") val asin: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("imagePath") val imagePath: String? = null,
    @SerialName("birthDate") val birthDate: String? = null,
    @SerialName("deathDate") val deathDate: String? = null,
    @SerialName("website") val website: String? = null,
) {
    init {
        name?.let { require(it.isNotBlank() && it.length <= MAX_NAME) { "name must be 1..$MAX_NAME chars" } }
        sortName?.let { require(it.length <= MAX_NAME) { "sortName must be <= $MAX_NAME chars" } }
        asin?.let { require(it.length <= MAX_ASIN) { "asin must be <= $MAX_ASIN chars" } }
        description?.let { require(it.length <= MAX_DESCRIPTION) { "description must be <= $MAX_DESCRIPTION chars" } }
        imagePath?.let { require(it.length <= MAX_PATH) { "imagePath must be <= $MAX_PATH chars" } }
        website?.let { require(it.length <= MAX_URL) { "website must be <= $MAX_URL chars" } }
    }

    companion object {
        const val MAX_NAME = 500
        const val MAX_ASIN = 20
        const val MAX_DESCRIPTION = 10_000
        const val MAX_PATH = 1024
        const val MAX_URL = 1024
    }
}
