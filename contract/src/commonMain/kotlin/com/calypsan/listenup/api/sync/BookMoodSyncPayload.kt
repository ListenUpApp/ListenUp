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
 *
 * [id] is an opaque per-row identity minted at creation (server-side by default;
 * client-side for offline-first creates). It deliberately encodes nothing about
 * [bookId] or [moodId] — an ungated tombstone that shipped a composite id would leak
 * the association to a user who never had access to the row (SERVER-SYNC-04).
 */
@Serializable
@SerialName("BookMoodSyncPayload")
data class BookMoodSyncPayload(
    /** Opaque per-row sync identity — encodes neither [bookId] nor [moodId]. */
    @SerialName("id") override val id: String,
    /** The book this mood is linked to. */
    @SerialName("bookId") val bookId: String,
    /** The mood linked to the book. */
    @SerialName("moodId") val moodId: String,
    /** Epoch millis when this junction row was first created. */
    @SerialName("createdAt") val createdAt: Long,
    /** Sync revision counter — bumped on every write (create or soft-delete). */
    @SerialName("revision") override val revision: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload
