package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sync payload for the `book_moods` junction — one row per `(bookId, moodId)` pair.
 *
 * The junction is global (cross-user, curator model): one book has one shared mood
 * set on the server, and all clients converge on the same state through this payload.
 *
 * Soft-deletes are tombstoned via [deletedAt]. A non-null [deletedAt] indicates the
 * mood was removed from the book; the sync handler writes the tombstone into Room's
 * `book_moods` table and the UI reactively reflects the removal.
 *
 * [createdAt] records when the junction row was first created (epoch millis). Unlike
 * [Mood] and [Book], [BookMoodSyncPayload] has no [updatedAt] — the row is either live
 * or soft-deleted; partial updates do not apply.
 */
@Serializable
@SerialName("BookMoodSyncPayload")
data class BookMoodSyncPayload(
    /** The book this mood is linked to. */
    @SerialName("bookId") val bookId: String,
    /** The mood linked to the book. */
    @SerialName("moodId") val moodId: String,
    /** Epoch millis when this junction row was first created. */
    @SerialName("createdAt") val createdAt: Long,
    /** Sync revision counter — bumped on every write (create or soft-delete). */
    @SerialName("revision") override val revision: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload {
    /** Synthetic sync identity `"$bookId:$moodId"` — matches the server's envelope id. Not serialized. */
    override val id: String get() = "$bookId:$moodId"
}
