package com.calypsan.listenup.api.dto.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The offline-first outbox payload for a Story World entity lifecycle edit, riding the
 * `entities` outbox channel keyed by the entity's id.
 *
 * [Upsert] carries the full-field [EntityUpsert] snapshot: unlike the
 * PATCH-shaped mutations on other domains (e.g.
 * [com.calypsan.listenup.api.dto.SeriesMutation.Update]), a single create-or-update RPC
 * ([com.calypsan.listenup.api.EntityService.upsertEntity]) backs every entity write — a
 * brand-new (client-minted id) entity and an edit to an existing one both replay through the
 * same variant. [Delete] soft-deletes — maps to
 * [com.calypsan.listenup.api.EntityService.deleteEntity]. Both variants are last-write-wins /
 * idempotent (see [com.calypsan.listenup.api.EntityService]'s class KDoc), so the channel is
 * safe to re-fire. Mirrors [com.calypsan.listenup.api.dto.SeriesMutation] /
 * [com.calypsan.listenup.api.dto.ContributorMutation].
 */
@Serializable
sealed interface EntityMutation {
    /**
     * The full-field entity snapshot — maps to [com.calypsan.listenup.api.EntityService.upsertEntity].
     *
     * @property upsert the entity's complete current state.
     */
    @Serializable
    @SerialName("EntityMutation.Upsert")
    data class Upsert(
        @SerialName("upsert") val upsert: EntityUpsert,
    ) : EntityMutation

    /**
     * Soft-delete the entity — maps to [com.calypsan.listenup.api.EntityService.deleteEntity].
     */
    @Serializable
    @SerialName("EntityMutation.Delete")
    data object Delete : EntityMutation
}
