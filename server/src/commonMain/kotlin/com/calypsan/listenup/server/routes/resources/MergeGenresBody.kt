package com.calypsan.listenup.server.routes.resources

import com.calypsan.listenup.core.GenreId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST body for `POST /api/v1/genres/merge`.
 *
 * All books linked to [source] are re-linked to [target] (deduped via
 * INSERT-OR-IGNORE), aliases pointing at [source] are repointed at [target], and
 * [source] is tombstoned. [source] and [target] must differ — equal ids surface
 * as [com.calypsan.listenup.api.error.GenreError.MergeSelfTarget]. [source] must
 * have no live descendants — non-empty subtree surfaces as
 * [com.calypsan.listenup.api.error.GenreError.HasDescendants].
 */
@Serializable
@SerialName("MergeGenresBody")
data class MergeGenresBody(
    @SerialName("source") val source: GenreId,
    @SerialName("target") val target: GenreId,
)
