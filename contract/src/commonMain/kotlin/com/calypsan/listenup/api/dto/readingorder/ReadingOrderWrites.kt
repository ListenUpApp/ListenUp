package com.calypsan.listenup.api.dto.readingorder

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Full-field snapshot for a reading-order metadata update — the outbox payload for
 * the `reading_orders` write channel.
 *
 * Unlike the nullable-field PATCH DTOs (e.g. `SeriesUpdate`), every field is
 * present: the editing client always holds the current row, so the queued op is a
 * last-write-wins snapshot that replays onto
 * [com.calypsan.listenup.api.ReadingOrderService.updateReadingOrder].
 *
 * @property name New display name.
 * @property description New description.
 * @property attribution New attribution text.
 * @property isPrivate New privacy flag.
 */
@Serializable
@SerialName("ReadingOrderUpdate")
data class ReadingOrderUpdate(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("attribution") val attribution: String,
    @SerialName("isPrivate") val isPrivate: Boolean,
)

/**
 * A queued reading-order membership mutation — the outbox payload for the
 * `reading_order_books` write channel.
 *
 * One sealed hierarchy instead of three channels: the outbox keys channels 1:1 to
 * sync domains, and the junction domain has three mutation shapes. The sender
 * dispatches on the subtype to the matching
 * [com.calypsan.listenup.api.ReadingOrderService] RPC.
 */
@Serializable
sealed interface ReadingOrderBookWrite {
    /**
     * Append a book to a reading order (idempotent; server assigns the next sort order).
     *
     * @property readingOrderId The target reading order.
     * @property bookId The book to add.
     */
    @Serializable
    @SerialName("ReadingOrderBookWrite.Add")
    data class Add(
        @SerialName("readingOrderId") val readingOrderId: String,
        @SerialName("bookId") val bookId: String,
    ) : ReadingOrderBookWrite

    /**
     * Remove a book from a reading order (idempotent soft-delete).
     *
     * @property readingOrderId The target reading order.
     * @property bookId The book to remove.
     */
    @Serializable
    @SerialName("ReadingOrderBookWrite.Remove")
    data class Remove(
        @SerialName("readingOrderId") val readingOrderId: String,
        @SerialName("bookId") val bookId: String,
    ) : ReadingOrderBookWrite

    /**
     * Replace-set the book ordering of a reading order.
     *
     * @property readingOrderId The target reading order.
     * @property orderedBookIds The new full ordering of the reading order's live members.
     */
    @Serializable
    @SerialName("ReadingOrderBookWrite.Reorder")
    data class Reorder(
        @SerialName("readingOrderId") val readingOrderId: String,
        @SerialName("orderedBookIds") val orderedBookIds: List<String>,
    ) : ReadingOrderBookWrite
}

/**
 * Sets the caller's active reading order for one series (Integration Foundations
 * §5.4 follow-state) — both the RPC request and the outbox payload for the
 * `reading_order_follows` write channel.
 *
 * @property seriesId The series whose follow-state is being set.
 * @property activeReadingOrderId The reading order to follow, or null to fall back
 *   to the per-book frontier (the graceful floor).
 */
@Serializable
@SerialName("SetActiveReadingOrderRequest")
data class SetActiveReadingOrderRequest(
    @SerialName("seriesId") val seriesId: String,
    @SerialName("activeReadingOrderId") val activeReadingOrderId: String? = null,
)
