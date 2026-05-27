package com.calypsan.listenup.api.dto

import com.calypsan.listenup.core.GenreId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Read-time summary returned by [com.calypsan.listenup.api.GenreService.listGenres]
 * and curator screens. Carries enough hierarchy detail ([path], [parentId], [depth])
 * for the client to render the tree without a second lookup.
 *
 * [bookCount] is computed at read-time via a JOIN against `book_genres` — it is
 * intentionally **not** denormalized onto the `genres` table. Tags precedent: a
 * single read-time aggregate is cheaper than maintaining a counter against every
 * `setBookGenres` / `deleteBook` / scanner-reingest path.
 */
@Serializable
@SerialName("GenreSummary")
data class GenreSummary(
    @SerialName("id") val id: GenreId,
    @SerialName("name") val name: String,
    @SerialName("slug") val slug: String,
    @SerialName("path") val path: String,
    @SerialName("parentId") val parentId: GenreId? = null,
    @SerialName("depth") val depth: Int = 0,
    @SerialName("sortOrder") val sortOrder: Int = 0,
    @SerialName("bookCount") val bookCount: Int = 0,
)
