package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire row for one Story World event: a line in a world's log, optionally pinned to a moment in one of the
 * world's books.
 *
 * Library-shared, homed like [EntitySyncPayload]: exactly one of [homeSeriesId] / [homeBookId] is set.
 * Visibility follows the home **and** the anchor: a member sees the event iff they can see its home and,
 * when [bookId] is set, that book too. A null [bookId] means the event is pinned to no moment and is always
 * visible to whoever sees the world; a later listening-frontier gate (Story World PR C) compares
 * [positionMs] with the listener's high-water mark.
 *
 * Ids are plain strings on the wire, like every [SyncPayload]; the RPC surface types them.
 */
@Serializable
@SerialName("WorldEventSyncPayload")
data class WorldEventSyncPayload(
    /** Stable, client-minted id. */
    @SerialName("id") override val id: String,
    /** The series this event lives under; exclusive with [homeBookId]. */
    @SerialName("homeSeriesId") val homeSeriesId: String? = null,
    /** The book this event lives under; exclusive with [homeSeriesId]. */
    @SerialName("homeBookId") val homeBookId: String? = null,
    /** The book the event is pinned to, a live book of its world; null when it is pinned to no moment. */
    @SerialName("bookId") val bookId: String? = null,
    /** Milliseconds into [bookId]; set exactly when [bookId] is. 0 is the book's start ("known from the start"). */
    @SerialName("positionMs") val positionMs: Long? = null,
    /** What happened; [WorldEventType.UNKNOWN] for a type this build doesn't know. */
    @SerialName("type") val type: WorldEventType,
    /** Free text; may carry [com.calypsan.listenup.domain.storyworld.MentionTokens]. Blank on a tombstone. */
    @SerialName("text") val text: String = "",
    /** The type's qualifier: a JOINS role or a LEAVES "how". Null for every other type. */
    @SerialName("detail") val detail: String? = null,
    /** The entity the event is about, of the event's world. */
    @SerialName("subjectEntityId") val subjectEntityId: String? = null,
    /** The entity the subject is related to (a destination, a group, a people), of the event's world. */
    @SerialName("objectEntityId") val objectEntityId: String? = null,
    /**
     * Every entity of the event's world the event names — text tokens, subject and object — sorted.
     * Server-derived on every write; whatever a client sends is ignored.
     */
    @SerialName("mentionIds") val mentionIds: List<String> = emptyList(),
    /** Who created it; null for a row the server wrote itself. */
    @SerialName("createdBy") val createdBy: String? = null,
    /** Who last changed it; null for a server-made change (a book removal, a series merge). */
    @SerialName("updatedBy") val updatedBy: String? = null,
    /** Sync revision. */
    @SerialName("revision") override val revision: Long,
    /** Epoch ms of the last write, stamped by the server's clock. */
    @SerialName("updatedAt") val updatedAt: Long,
    /** Epoch ms of creation. */
    @SerialName("createdAt") val createdAt: Long,
    /** Tombstone instant, else null. */
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload
