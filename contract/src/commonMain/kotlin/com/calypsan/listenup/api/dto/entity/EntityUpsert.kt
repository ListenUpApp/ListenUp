package com.calypsan.listenup.api.dto.entity

import com.calypsan.listenup.api.sync.EntityKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Full-field snapshot for a Story World entity create-or-update — the outbox payload
 * for the `entities` write channel.
 *
 * Every field is present: the editing client always holds the current row (or is
 * minting a new one), so the queued op is a last-write-wins snapshot that replays
 * onto [com.calypsan.listenup.api.EntityService.upsertEntity].
 *
 * @property id Stable, client-minted identifier — random UUID, stable across a
 *   create (new id) and every subsequent edit (same id).
 * @property kind The entity's taxonomy — character, location, or item.
 * @property name The entity's first-introduced (pre-reveal) name; see
 *   [com.calypsan.listenup.api.sync.EntitySyncPayload.name] for the spoiler-safety
 *   authoring convention this must follow.
 * @property homeSeriesId The series this entity is namespaced under. Exactly one of
 *   [homeSeriesId] / [homeBookId] is non-null; see
 *   [com.calypsan.listenup.api.sync.EntitySyncPayload] for the dual-home rule.
 * @property homeBookId The standalone book this entity is namespaced under. Exactly
 *   one of [homeSeriesId] / [homeBookId] is non-null.
 * @property imageRef Reserved reference to an entity portrait/image; null until
 *   image support ships.
 */
@Serializable
@SerialName("EntityUpsert")
data class EntityUpsert(
    @SerialName("id") val id: String,
    @SerialName("kind") val kind: EntityKind,
    @SerialName("name") val name: String,
    @SerialName("homeSeriesId") val homeSeriesId: String? = null,
    @SerialName("homeBookId") val homeBookId: String? = null,
    @SerialName("imageRef") val imageRef: String? = null,
)
