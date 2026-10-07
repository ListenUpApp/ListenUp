package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.StoryWorldHistoryId

/**
 * A Story World entity as the UI reads it: a character, place, item, group, people, event or concept.
 * Named `WorldEntity` rather than `Entity` because `Entity` collides with every Swift codebase that imports
 * the shared framework. [kind] is [EntityKind.UNKNOWN] for a kind this build doesn't know yet; render it as
 * a generic entry.
 *
 * @property homeSeriesId the series it belongs to; exclusive with [homeBookId].
 * @property homeBookId the book it belongs to; exclusive with [homeSeriesId].
 */
data class WorldEntity(
    val id: EntityId,
    val kind: EntityKind,
    val name: String,
    val descriptor: String?,
    val parentId: EntityId?,
    val homeSeriesId: SeriesId?,
    val homeBookId: BookId?,
    val createdBy: UserId?,
    val updatedBy: UserId?,
)

/** What a user fills in to create an entity. Exactly one of [homeSeriesId] / [homeBookId] must be set. */
data class WorldEntityDraft(
    val kind: EntityKind,
    val name: String,
    val descriptor: String? = null,
    val parentId: EntityId? = null,
    val homeSeriesId: SeriesId? = null,
    val homeBookId: BookId? = null,
)

/**
 * A just-made edit, kept so the UI can offer an immediate Undo (see `EntityEditRepository.undo`).
 *
 * @property before the entity as it was; null for a create.
 * @property wasDelete true when the edit deleted the entity — its undo is a revival, not a forward write.
 */
data class EntityEdit(
    val entityId: EntityId,
    val before: WorldEntity?,
    val wasDelete: Boolean = false,
)

/** One entry of an entity's history, as read from the server (see `EntityService.listHistory`). */
data class WorldEntityChange(
    val id: StoryWorldHistoryId,
    val entityId: EntityId,
    val op: StoryWorldOp,
    val actorId: UserId?,
    val occurredAtMs: Long,
    val before: WorldEntity?,
    val after: WorldEntity?,
)
