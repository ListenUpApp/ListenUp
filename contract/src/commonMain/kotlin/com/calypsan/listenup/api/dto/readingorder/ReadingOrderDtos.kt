package com.calypsan.listenup.api.dto.readingorder

import com.calypsan.listenup.core.ReadingOrderId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Summary DTO for a single reading order, used in list views.
 *
 * Returned by [com.calypsan.listenup.api.ReadingOrderService.listMyReadingOrders]
 * and embedded inside [DiscoveredReadingOrder]. Carries the reading order identity,
 * display metadata, and a live [bookCount] computed at query time.
 *
 * @property id Stable reading-order identifier.
 * @property name Display name of the reading order (e.g. "Cosmere — Chronological").
 * @property description Optional description of the reading order's theme or purpose.
 * @property attribution Free text — who recommends this order / why.
 * @property isPrivate `true` if only the owner can see this reading order.
 * @property bookCount Number of non-deleted books currently in the reading order.
 * @property updatedAt Epoch millis of the last server-side write to this reading order.
 */
@Serializable
@SerialName("ReadingOrder")
data class ReadingOrder(
    @SerialName("id") val id: ReadingOrderId,
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("attribution") val attribution: String,
    @SerialName("isPrivate") val isPrivate: Boolean,
    @SerialName("bookCount") val bookCount: Int,
    @SerialName("updatedAt") val updatedAt: Long,
)

/**
 * Slim book view within a reading-order detail response.
 *
 * Carries only the display-critical fields so the client can render the reading
 * order's book list without a separate `BookService.getBook` call for each entry.
 *
 * @property bookId Stable book identifier.
 * @property title Display title of the book.
 * @property authors Ordered list of author display names.
 */
@Serializable
@SerialName("ReadingOrderBookView")
data class ReadingOrderBookView(
    @SerialName("bookId") val bookId: String,
    @SerialName("title") val title: String,
    @SerialName("authors") val authors: List<String>,
)

/**
 * Full reading-order detail DTO, returned by
 * [com.calypsan.listenup.api.ReadingOrderService.getReadingOrder].
 *
 * Contains the complete, ordered book list (access-filtered for non-owners) and
 * aggregate stats. [isOwner] lets the client adapt the UI between owner (edit) and
 * viewer (read-only) modes without a separate identity check.
 *
 * @property id Stable reading-order identifier.
 * @property name Display name of the reading order.
 * @property description Optional description.
 * @property attribution Free text — who recommends this order / why.
 * @property isPrivate `true` if only the owner can see this reading order.
 * @property isOwner `true` when the caller is the reading-order owner.
 * @property books Ordered, access-filtered list of books in the reading order.
 * @property bookCount Total number of books visible to the caller.
 * @property totalDurationMs Sum of all visible books' audio duration in milliseconds.
 */
@Serializable
@SerialName("ReadingOrderDetail")
data class ReadingOrderDetail(
    @SerialName("id") val id: ReadingOrderId,
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("attribution") val attribution: String,
    @SerialName("isPrivate") val isPrivate: Boolean,
    @SerialName("isOwner") val isOwner: Boolean,
    @SerialName("books") val books: List<ReadingOrderBookView>,
    @SerialName("bookCount") val bookCount: Int,
    @SerialName("totalDurationMs") val totalDurationMs: Long,
)

/**
 * A reading order discovered via
 * [com.calypsan.listenup.api.ReadingOrderService.discoverReadingOrders].
 *
 * Wraps a [ReadingOrder] summary with the owner's identity and display name so the
 * discovery screen can show "Sam's Cosmere Order" without a separate user lookup.
 * Only public reading orders with at least one book accessible to the caller are
 * returned.
 *
 * @property readingOrder The reading-order summary (book count reflects only accessible books).
 * @property ownerId Stable user ID of the reading-order owner.
 * @property ownerDisplayName Display name of the reading-order owner for UI labeling.
 */
@Serializable
@SerialName("DiscoveredReadingOrder")
data class DiscoveredReadingOrder(
    @SerialName("readingOrder") val readingOrder: ReadingOrder,
    @SerialName("ownerId") val ownerId: String,
    @SerialName("ownerDisplayName") val ownerDisplayName: String,
)
