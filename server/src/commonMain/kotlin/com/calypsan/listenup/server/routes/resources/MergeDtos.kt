package com.calypsan.listenup.server.routes.resources

import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST body for `POST /api/v1/contributors/merge`.
 *
 * All books linked to [source] are re-linked to [target]; [source] is
 * tombstoned and removed from search. [source] and [target] must differ.
 */
@Serializable
@SerialName("MergeContributorsBody")
data class MergeContributorsBody(
    @SerialName("source") val source: ContributorId,
    @SerialName("target") val target: ContributorId,
)

/**
 * POST body for `POST /api/v1/contributors/{id}/unmerge`.
 *
 * [aliasName] must be a name present in the contributor's alias list. A fresh
 * contributor is created with `name = aliasName`, and every
 * `book_contributors` row where `credited_as = aliasName` is re-linked to the
 * new contributor.
 */
@Serializable
@SerialName("UnmergeContributorBody")
data class UnmergeContributorBody(
    @SerialName("aliasName") val aliasName: String,
)

/**
 * POST body for `POST /api/v1/series/merge`.
 *
 * All books belonging to [source] are re-linked to [target]; [source] is
 * tombstoned. [source] and [target] must differ.
 */
@Serializable
@SerialName("MergeSeriesBody")
data class MergeSeriesBody(
    @SerialName("source") val source: SeriesId,
    @SerialName("target") val target: SeriesId,
)
