package com.calypsan.listenup.api.dto

import kotlinx.serialization.Serializable

/**
 * PATCH payload for [com.calypsan.listenup.api.ContributorService.updateContributor].
 *
 * Every field is nullable — `null` means "don't touch." Aliases are **not**
 * present in C1 because contributor aliases are not yet a server-side concept;
 * the C2 phase introduces the `contributor_aliases` server substrate.
 */
@Serializable
data class ContributorUpdate(
    val name: String? = null,
    val sortName: String? = null,
    val asin: String? = null,
    val description: String? = null,
    val imagePath: String? = null,
    val birthDate: String? = null,
    val deathDate: String? = null,
    val website: String? = null,
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
