package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PATCH payload for [com.calypsan.listenup.api.GenreService.updateGenre].
 *
 * Every field is nullable — `null` means "don't touch." Path, parent, and slug
 * are intentionally omitted: structural moves go through
 * [com.calypsan.listenup.api.GenreService.moveGenre] and slug is derived from
 * `name` server-side. Mirrors the [ContributorUpdate] / [SeriesUpdate] shape.
 *
 * Field validation runs in [init] so deserialized instances always carry valid
 * data — the same constraints the server's `init { require }` would enforce.
 *
 * - [name] — display name, 1..[MAX_NAME] chars when non-null.
 * - [description] — free-form blurb, up to [MAX_DESCRIPTION] chars when non-null.
 * - [color] — hex color of the form `#RRGGBB` (matches [HEX_COLOR_REGEX]).
 * - [sortOrder] — manual ordering hint within siblings, [MIN_SORT]..[MAX_SORT].
 */
@Serializable
@SerialName("GenreUpdate")
data class GenreUpdate(
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("color") val color: String? = null,
    @SerialName("sortOrder") val sortOrder: Int? = null,
) {
    init {
        name?.let { require(it.isNotBlank() && it.length <= MAX_NAME) { "name must be 1..$MAX_NAME chars" } }
        description?.let { require(it.length <= MAX_DESCRIPTION) { "description must be <= $MAX_DESCRIPTION chars" } }
        color?.let { require(it.matches(HEX_COLOR_REGEX)) { "color must match $HEX_COLOR_REGEX" } }
        sortOrder?.let { require(it in MIN_SORT..MAX_SORT) { "sortOrder must be $MIN_SORT..$MAX_SORT" } }
    }

    companion object {
        const val MAX_NAME = 200
        const val MAX_DESCRIPTION = 2_000
        const val MIN_SORT = -10_000
        const val MAX_SORT = 10_000
        val HEX_COLOR_REGEX = Regex("^#[0-9a-fA-F]{6}$")
    }
}
