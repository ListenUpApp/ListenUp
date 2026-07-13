package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The offline-first outbox payload for a contributor lifecycle edit, riding the `contributors`
 * outbox channel keyed by the contributor's id.
 *
 * Each variant carries exactly the arguments its backing
 * [com.calypsan.listenup.api.ContributorService] method needs. Both variants are last-write-wins /
 * idempotent, so the channel is safe to re-fire. Merging two contributors
 * ([com.calypsan.listenup.api.ContributorService.mergeContributors]) and un-merging an alias
 * ([com.calypsan.listenup.api.ContributorService.unmergeContributor]) stay online RPCs — they relink
 * junctions and mint identities server-side, so they can't be mirrored optimistically. Mirrors
 * [SeriesMutation] / [TagMutation].
 */
@Serializable
sealed interface ContributorMutation {
    /**
     * A metadata PATCH — maps to [com.calypsan.listenup.api.ContributorService.updateContributor].
     *
     * @property patch the per-field PATCH; null fields leave existing state untouched.
     */
    @Serializable
    @SerialName("ContributorMutation.Update")
    data class Update(
        @SerialName("patch") val patch: ContributorUpdate,
    ) : ContributorMutation

    /**
     * Delete the contributor and cascade-remove its `book_contributors` credits — maps to
     * [com.calypsan.listenup.api.ContributorService.deleteContributor].
     */
    @Serializable
    @SerialName("ContributorMutation.Delete")
    data object Delete : ContributorMutation
}
