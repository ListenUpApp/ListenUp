package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sync payload for the `book_tags` junction — one row per `(bookId, tagId)` pair.
 *
 * The junction is global (cross-user, curator model): one book has one shared tag
 * set on the server, and all clients converge on the same state through this payload.
 *
 * Soft-deletes are tombstoned via [deletedAt]. A non-null [deletedAt] indicates the
 * tag was removed from the book; the sync handler writes the tombstone into Room's
 * `book_tags` table and the UI reactively reflects the removal.
 *
 * [createdAt] records when the junction row was first created (epoch millis). Unlike
 * [Tag] and [Book], [BookTagSyncPayload] has no [updatedAt] — the row is either live
 * or soft-deleted; partial updates do not apply.
 */
@Serializable
@SerialName("BookTagSyncPayload")
data class BookTagSyncPayload(
    /** The book this tag is linked to. */
    @SerialName("bookId") val bookId: String,
    /** The tag linked to the book. */
    @SerialName("tagId") val tagId: String,
    /** Epoch millis when this junction row was first created. */
    @SerialName("createdAt") val createdAt: Long,
    /** Sync revision counter — bumped on every write (create or soft-delete). */
    @SerialName("revision") override val revision: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload {
    /** Synthetic sync identity `"$bookId:$tagId"` — matches the server's envelope id. Not serialized. */
    override val id: String get() = "$bookId:$tagId"
}
