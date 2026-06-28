package com.calypsan.listenup.server.routes.resources

import com.calypsan.listenup.core.GenreId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST body for `POST /api/v1/genres`.
 *
 * Creates a new genre under [parentId] (or as a root when [parentId] is null)
 * with the given [name] and [sortOrder]. The server derives the slug from
 * [name] deterministically. Unknown [parentId] surfaces as
 * [com.calypsan.listenup.api.error.GenreError.NotFound]; a slug collision with
 * any existing live genre surfaces as
 * [com.calypsan.listenup.api.error.GenreError.SlugConflict].
 */
@Serializable
@SerialName("CreateGenreBody")
data class CreateGenreBody(
    @SerialName("parentId") val parentId: GenreId? = null,
    @SerialName("name") val name: String,
    @SerialName("sortOrder") val sortOrder: Int = 0,
)
