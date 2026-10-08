package com.calypsan.listenup.api.dto.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `entities` outbox payload, keyed by the entity id. [Upsert] maps to
 * [com.calypsan.listenup.api.EntityService.upsertEntity] (create and edit alike, arrival-order
 * overwrite); [Delete] maps to [com.calypsan.listenup.api.EntityService.deleteEntity]. Both are safe
 * to re-fire.
 */
@Serializable
sealed interface EntityMutation {
    /** Create or edit with the full snapshot. */
    @Serializable
    @SerialName("EntityMutation.Upsert")
    data class Upsert(
        @SerialName("upsert") val upsert: EntityUpsert,
    ) : EntityMutation

    /** Soft-delete the entity. */
    @Serializable
    @SerialName("EntityMutation.Delete")
    data object Delete : EntityMutation
}
