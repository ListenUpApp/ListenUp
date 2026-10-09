package com.calypsan.listenup.api.dto.worldevent

import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.WorldEventId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The full-field snapshot a client sends to create or edit one world event, inside an [EventsBatch].
 *
 * A new [id] creates; an existing one edits. The home is fixed at creation. Mentions and authorship
 * are the server's to set, so they are not here. Conflicts resolve in arrival order, and history makes an
 * overwritten edit one revert away.
 *
 * @property id client-minted event id.
 * @property type what happened; [WorldEventType.UNKNOWN] is refused on create and keeps the stored type on edit.
 * @property text free text, possibly carrying mention tokens; required only for a NOTE.
 * @property detail a JOINS role or a LEAVES "how"; refused on any other type.
 * @property homeSeriesId the series home; exclusive with [homeBookId].
 * @property homeBookId the book home; exclusive with [homeSeriesId].
 * @property bookId the anchor book; set together with [positionMs], or neither.
 * @property positionMs milliseconds into [bookId], never negative.
 * @property subjectEntityId the entity the event is about.
 * @property objectEntityId the entity the subject is related to.
 */
@Serializable
@SerialName("WorldEventUpsert")
data class WorldEventUpsert(
    @SerialName("id") val id: WorldEventId,
    @SerialName("type") val type: WorldEventType,
    @SerialName("text") val text: String = "",
    @SerialName("detail") val detail: String? = null,
    @SerialName("homeSeriesId") val homeSeriesId: SeriesId? = null,
    @SerialName("homeBookId") val homeBookId: BookId? = null,
    @SerialName("bookId") val bookId: BookId? = null,
    @SerialName("positionMs") val positionMs: Long? = null,
    @SerialName("subjectEntityId") val subjectEntityId: EntityId? = null,
    @SerialName("objectEntityId") val objectEntityId: EntityId? = null,
)
