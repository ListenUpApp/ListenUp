package com.calypsan.listenup.server.routes.resources

import com.calypsan.listenup.core.GenreId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST body for `POST /api/v1/genres/{id}/move`.
 *
 * Reparents the genre identified by the path's `id` to [newParentId]. A null
 * value moves the genre to the root. Cycles (moving a genre under itself or one
 * of its own descendants) surface as
 * [com.calypsan.listenup.api.error.GenreError.MoveSelfDescendant]. The server
 * rewrites the moved subtree's materialized `path` and `depth` in a single
 * transaction.
 */
@Serializable
@SerialName("MoveGenreBody")
data class MoveGenreBody(
    @SerialName("newParentId") val newParentId: GenreId? = null,
)
