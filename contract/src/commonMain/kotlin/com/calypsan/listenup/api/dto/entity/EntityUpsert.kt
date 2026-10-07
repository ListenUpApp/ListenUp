package com.calypsan.listenup.api.dto.entity

import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The full-field snapshot a client sends to create or edit an entity — the `entities` outbox payload
 * and the argument to [com.calypsan.listenup.api.EntityService.upsertEntity].
 *
 * A new [id] creates; an existing one edits. The home is fixed at creation: an edit that names a
 * different home is refused. Conflicts resolve as everywhere else in sync: the write that arrives at
 * the server last overwrites, and the edit history makes a lost edit one revert away.
 *
 * @property id client-minted entity id.
 * @property kind the entity's kind; [EntityKind.UNKNOWN] is refused on create and means "keep the
 *   stored kind" on an edit.
 * @property name the first-met name.
 * @property descriptor optional subtitle, at most 60 characters.
 * @property parentId optional containing entity.
 * @property homeSeriesId the series home; exclusive with [homeBookId].
 * @property homeBookId the book home; exclusive with [homeSeriesId].
 */
@Serializable
@SerialName("EntityUpsert")
data class EntityUpsert(
    @SerialName("id") val id: EntityId,
    @SerialName("kind") val kind: EntityKind,
    @SerialName("name") val name: String,
    @SerialName("descriptor") val descriptor: String? = null,
    @SerialName("parentId") val parentId: EntityId? = null,
    @SerialName("homeSeriesId") val homeSeriesId: SeriesId? = null,
    @SerialName("homeBookId") val homeBookId: BookId? = null,
)
