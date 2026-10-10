package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.StoryWorldHistoryId
import com.calypsan.listenup.core.WorldEventId

/**
 * The moment in one of a world's books that a Story World event is pinned to.
 *
 * @property positionMs milliseconds into [bookId]; 0 is the book's start ("known from the start").
 */
data class WorldEventAnchor(
    val bookId: BookId,
    val positionMs: Long,
)

/**
 * What a Story World event says — everything a person writes, nothing the server derives. Which parts a [type]
 * needs is the shared rule set's to say (`WorldEventRules`).
 *
 * @property text free text; may carry mention tokens (`MentionTokens.token`). Required only for a NOTE.
 * @property detail a JOINS role or a LEAVES "how"; null for every other type.
 * @property anchor the moment it is pinned to, or null for always visible.
 * @property subjectId the entity the event is about.
 * @property objectId the entity the subject is related to.
 */
data class WorldEventContent(
    val type: WorldEventType,
    val text: String = "",
    val detail: String? = null,
    val anchor: WorldEventAnchor? = null,
    val subjectId: EntityId? = null,
    val objectId: EntityId? = null,
)

/**
 * A Story World event as the UI reads it: one line of a world's log. Not an
 * [com.calypsan.listenup.api.sync.EntityKind.EVENT] entity (a named happening), which an event can mention.
 * [content]'s type is [WorldEventType.UNKNOWN] for a type this build doesn't know; render it as a note.
 *
 * @property homeSeriesId the series it belongs to; exclusive with [homeBookId].
 * @property homeBookId the book it belongs to; exclusive with [homeSeriesId].
 * @property mentionIds the entities of its world it names — the server's set once synced.
 */
data class WorldEvent(
    val id: WorldEventId,
    val homeSeriesId: SeriesId?,
    val homeBookId: BookId?,
    val content: WorldEventContent,
    val mentionIds: Set<EntityId>,
    val createdBy: UserId?,
    val updatedBy: UserId?,
)

/** What a user fills in to record an event. Exactly one of [homeSeriesId] / [homeBookId] must be set. */
data class WorldEventDraft(
    val content: WorldEventContent,
    val homeSeriesId: SeriesId? = null,
    val homeBookId: BookId? = null,
)

/**
 * A just-made edit, kept so the UI can offer an immediate Undo (see `WorldEventEditRepository.undo`).
 *
 * @property before the event as it was; null for a create.
 * @property wasDelete true when the edit deleted the event — its undo is a revival, not a forward write.
 */
data class WorldEventEdit(
    val eventId: WorldEventId,
    val before: WorldEvent?,
    val wasDelete: Boolean = false,
)

/** What one [WorldEventChange] did to its event. Events are never merged. */
enum class WorldEventChangeOp {
    CREATE,
    UPDATE,
    DELETE,

    /** Restored an earlier change's `before`; itself revertible. */
    REVERT,
}

/**
 * One entry of an event's history, as read from the server (see `WorldEventService.listHistory`).
 *
 * @property actorId who made the change; null for a change the server made itself.
 * @property before the event before the change; null for a create, or when the server hid a state whose home or
 *   anchor this user can't see.
 * @property after the event after the change; null when the server hid it.
 */
data class WorldEventChange(
    val id: StoryWorldHistoryId,
    val eventId: WorldEventId,
    val op: WorldEventChangeOp,
    val actorId: UserId?,
    val occurredAtMs: Long,
    val before: WorldEvent?,
    val after: WorldEvent?,
)
