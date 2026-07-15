package com.calypsan.listenup.api.dto.entity

import com.calypsan.listenup.api.sync.BioEntryPayload
import com.calypsan.listenup.api.sync.EntityKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Full-field snapshot for a Story World entity create-or-update — the outbox payload
 * for the `entities` write channel.
 *
 * Every field is present: the editing client always holds the current row (or is
 * minting a new one), so the queued op is a last-write-wins snapshot that replays
 * onto [com.calypsan.listenup.api.EntityService.upsertEntity]. [bioEntries] is a
 * whole-aggregate replace — the server discards and re-inserts the entity's entire
 * bio-entry child set on every write, mirroring how a book's chapter list is
 * replaced wholesale (there is no incremental per-entry write).
 *
 * @property id Stable, client-minted identifier — random UUID, stable across a
 *   create (new id) and every subsequent edit (same id).
 * @property kind The entity's taxonomy — character, location, or item.
 * @property name The entity's first-introduced (pre-reveal) name; see
 *   [com.calypsan.listenup.api.sync.EntitySyncPayload.name] for the spoiler-safety
 *   authoring convention this must follow.
 * @property homeSeriesId The series this entity is namespaced under.
 * @property imageRef Reserved reference to an entity portrait/image; null until
 *   image support ships.
 * @property bioEntries The entity's full, replace-wholesale bio-entry set.
 */
@Serializable
@SerialName("EntityUpsert")
data class EntityUpsert(
    @SerialName("id") val id: String,
    @SerialName("kind") val kind: EntityKind,
    @SerialName("name") val name: String,
    @SerialName("homeSeriesId") val homeSeriesId: String,
    @SerialName("imageRef") val imageRef: String? = null,
    @SerialName("bioEntries") val bioEntries: List<BioEntryPayload> = emptyList(),
)
