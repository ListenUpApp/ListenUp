package com.calypsan.listenup.api.dto

import com.calypsan.listenup.core.GenreId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST body for `POST /api/v1/genres/unmapped/map`.
 *
 * Maps every book currently linked to [rawString] in `pending_book_genres` to
 * [genreId]: inserts the `(book_id, genre_id)` rows (INSERT-OR-IGNORE), records
 * [rawString] as an alias for [genreId], and removes the pending rows. Unknown
 * [rawString] surfaces as
 * [com.calypsan.listenup.api.error.GenreError.UnmappedStringNotFound]; unknown
 * [genreId] surfaces as [com.calypsan.listenup.api.error.GenreError.NotFound].
 */
@Serializable
@SerialName("MapUnmappedBody")
data class MapUnmappedBody(
    @SerialName("rawString") val rawString: String,
    @SerialName("genreId") val genreId: GenreId,
)
