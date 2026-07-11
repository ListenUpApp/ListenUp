package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ReadingOrderId
import kotlin.time.Duration.Companion.seconds

/**
 * Domain model for a personal reading order — a user-owned, named, **ordered**,
 * attributed list of books (e.g. "Cosmere — Chronological"). The near-exact
 * sibling of [Shelf], plus [attribution].
 *
 * Owner identity (`ownerId`, `ownerDisplayName`) is populated from the current
 * user for the caller's own orders and from the server for discovered orders.
 * `coverPaths` and `totalDurationSeconds` are derived from the order's member
 * books in the local Room mirror — discovered (other-user) orders carry empty
 * covers and zero duration because their member books never enter the mirror.
 *
 * @property id Unique identifier (typed [ReadingOrderId])
 * @property name Display name (e.g., "Cosmere — Chronological")
 * @property description Optional description
 * @property attribution Free text — who recommends this order / why (empty when unset)
 * @property isPrivate `true` if only the owner can see this reading order
 * @property ownerId User who created this reading order
 * @property ownerDisplayName Owner's display name for social context
 * @property bookCount Number of books in this reading order
 * @property totalDurationSeconds Total duration of all books in seconds
 * @property createdAtMs Creation timestamp
 * @property updatedAtMs Last update timestamp
 * @property coverPaths Cover hashes for the order's first few member books, in sort order
 */
data class ReadingOrder(
    val id: ReadingOrderId,
    val name: String,
    val description: String?,
    val attribution: String,
    val isPrivate: Boolean,
    val ownerId: String,
    val ownerDisplayName: String,
    val bookCount: Int,
    val totalDurationSeconds: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val coverPaths: List<String> = emptyList(),
) {
    /** The reading-order id as a plain String, for the Swift/SKIE boundary (the value class is unboxed there). */
    val idString: String get() = id.value

    /** Returns true if this reading order belongs to the given user. */
    fun isOwnedBy(userId: String): Boolean = ownerId == userId

    /** Returns the total duration formatted as hours and minutes. */
    val formattedDuration: String
        get() = DurationFormatter.hoursMinutesCompact(totalDurationSeconds.seconds)
}

/**
 * Domain model representing full reading-order details.
 *
 * For non-owners, [books] is already access-filtered by the server — books the
 * viewer cannot access are silently excluded, so [bookCount] reflects only the
 * visible set. [books] preserves the order's sort order.
 *
 * @property id Unique reading-order identifier (typed [ReadingOrderId])
 * @property name Reading-order display name
 * @property description Optional description
 * @property attribution Free text — who recommends this order / why (empty when unset)
 * @property isPrivate `true` if only the owner can see this reading order
 * @property isOwner `true` when the current user owns this order (gates edit/delete/reorder)
 * @property bookCount Number of books visible to the viewer
 * @property totalDurationSeconds Total duration of all visible books in seconds
 * @property books Ordered list of books in the reading order
 */
data class ReadingOrderDetail(
    val id: ReadingOrderId,
    val name: String,
    val description: String?,
    val attribution: String,
    val isPrivate: Boolean,
    val isOwner: Boolean,
    val bookCount: Int,
    val totalDurationSeconds: Long,
    val books: List<ReadingOrderBook>,
) {
    /** The reading-order id as a plain String, for the Swift/SKIE boundary (the value class is unboxed there). */
    val idString: String get() = id.value

    /** Returns the total duration formatted as hours and minutes. */
    val formattedDuration: String
        get() = DurationFormatter.hoursMinutesCompact(totalDurationSeconds.seconds)
}

/**
 * A book within a reading order.
 *
 * The server's reading-order book view supplies only display-critical fields;
 * per-book duration is not part of the contract, so it is omitted here.
 * [coverHash] is resolved client-side from the local `books` mirror when available.
 *
 * @property id Book's unique identifier (typed [BookId])
 * @property title Book title
 * @property authorNames List of author names for this book
 * @property coverHash Content hash of the book's cover, used to bust the image cache
 *   when the cover changes (optional; resolved from the local `books` mirror)
 */
data class ReadingOrderBook(
    val id: BookId,
    val title: String,
    val authorNames: List<String>,
    val coverHash: String? = null,
) {
    /** The book id as a plain String, for the Swift/SKIE boundary (the value class is unboxed there). */
    val idString: String get() = id.value
}
