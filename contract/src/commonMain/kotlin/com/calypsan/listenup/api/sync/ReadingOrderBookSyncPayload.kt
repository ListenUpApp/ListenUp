package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One book's place in one reading order (#962). [id] is an opaque client-minted wire id
 * (SERVER-SYNC-04), never the natural pair. Per-row access-gated on [bookId]; tombstones ship with
 * [readingOrderId] and [bookId] blanked, so a member never learns an association for a book they can't
 * see. [position] orders the members, lowest first; gaps are allowed (clients number by rank).
 */
@Serializable
@SerialName("ReadingOrderBookSyncPayload")
data class ReadingOrderBookSyncPayload(
    /** Opaque per-row sync identity — encodes neither [readingOrderId] nor [bookId]. */
    @SerialName("id") override val id: String,
    /** The order this row places [bookId] in. */
    @SerialName("readingOrderId") val readingOrderId: String,
    /** The book. */
    @SerialName("bookId") val bookId: String,
    /** Place in the order, lowest first; sparse. */
    @SerialName("position") val position: Int,
    /** Sync revision counter — bumped on every write. */
    @SerialName("revision") override val revision: Long,
    /** Epoch millis of the last server-side write. */
    @SerialName("updatedAt") val updatedAt: Long,
    /** Epoch millis when the book was added. */
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload
