package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One listener's rating of one book — the `book_ratings` sync row. There is at most one live row
 * per `(bookId, userId)`; everyone who can open [bookId] receives every row for it, which is the
 * readers-section rule (anyone who can open a book sees who read it and what they thought).
 *
 * [id] is opaque (SERVER-SYNC-04) and encodes neither [bookId] nor [userId]: a tombstone ships with
 * both blanked, so it tells a member who never had access nothing.
 */
@Serializable
@SerialName("BookRatingSyncPayload")
data class BookRatingSyncPayload(
    /** Opaque per-row sync identity. */
    @SerialName("id") override val id: String,
    /** The rated book. */
    @SerialName("bookId") val bookId: String,
    /** The listener who rated it. */
    @SerialName("userId") val userId: String,
    /** 2..10 half-star units — see [com.calypsan.listenup.domain.ListenerRatingLimits]. */
    @SerialName("halfStars") val halfStars: Int,
    /** The listener's short note, or null. */
    @SerialName("note") val note: String?,
    /** Epoch ms the listener first rated this book; kept across edits, reset when re-rated after a clear. */
    @SerialName("ratedAt") val ratedAt: Long,
    /** Epoch ms of the latest edit. */
    @SerialName("updatedAt") val updatedAt: Long,
    /** Sync revision, bumped on every write. */
    @SerialName("revision") override val revision: Long,
    /** Tombstone instant when the rating was cleared, else null. */
    @SerialName("deletedAt") override val deletedAt: Long? = null,
    /** Where this rating came from: Hardcover-imported until the listener touches it. Absent on older frames = [ListenerRatingSource.LISTENUP]. */
    @SerialName("source") val source: ListenerRatingSource = ListenerRatingSource.LISTENUP,
) : SyncPayload
