package com.calypsan.listenup.api.dto

import com.calypsan.listenup.api.dto.readingorder.ReadingOrderChoiceKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The offline-first outbox payload for a reading order's lifecycle (#962), riding the `reading_orders`
 * outbox channel keyed by the order's client-minted id. Unlike shelves, create is modelled here: the
 * client mints the id, so the server can replay a create idempotently.
 */
@Serializable
sealed interface ReadingOrderMutation {
    /**
     * Make the order, under the id the op is keyed by — maps to
     * [com.calypsan.listenup.api.ReadingOrderService.createReadingOrder].
     *
     * @property seriesId the series the order belongs to.
     * @property name its display name.
     */
    @Serializable
    @SerialName("ReadingOrderMutation.Create")
    data class Create(
        @SerialName("seriesId") val seriesId: String,
        @SerialName("name") val name: String,
    ) : ReadingOrderMutation

    /**
     * Rename it — maps to [com.calypsan.listenup.api.ReadingOrderService.renameReadingOrder].
     *
     * @property name the new display name.
     */
    @Serializable
    @SerialName("ReadingOrderMutation.Rename")
    data class Rename(
        @SerialName("name") val name: String,
    ) : ReadingOrderMutation

    /**
     * Delete it; its memberships and everyone's follows of it go too — maps to
     * [com.calypsan.listenup.api.ReadingOrderService.deleteReadingOrder].
     */
    @Serializable
    @SerialName("ReadingOrderMutation.Delete")
    data object Delete : ReadingOrderMutation
}

/**
 * The offline-first outbox payload for a reading order's membership (#962), riding the
 * `reading_order_books` channel. Add and remove are keyed by the junction; reorder is keyed by the order
 * id and coalesces, and the server merges it tolerantly, so it may land either side of an add or remove.
 */
@Serializable
sealed interface ReadingOrderBookMutation {
    /**
     * Append [bookId] to [readingOrderId] under the client-minted [membershipId] — maps to
     * [com.calypsan.listenup.api.ReadingOrderService.addBookToReadingOrder]. Idempotent.
     *
     * @property membershipId the opaque wire id the client minted for the row.
     * @property readingOrderId the order gaining the book.
     * @property bookId the book.
     */
    @Serializable
    @SerialName("ReadingOrderBookMutation.Add")
    data class Add(
        @SerialName("membershipId") val membershipId: String,
        @SerialName("readingOrderId") val readingOrderId: String,
        @SerialName("bookId") val bookId: String,
    ) : ReadingOrderBookMutation

    /**
     * Take [bookId] out of [readingOrderId] — maps to
     * [com.calypsan.listenup.api.ReadingOrderService.removeBookFromReadingOrder]. Idempotent.
     *
     * @property readingOrderId the order losing the book.
     * @property bookId the book.
     */
    @Serializable
    @SerialName("ReadingOrderBookMutation.Remove")
    data class Remove(
        @SerialName("readingOrderId") val readingOrderId: String,
        @SerialName("bookId") val bookId: String,
    ) : ReadingOrderBookMutation

    /**
     * Set the order's whole sequence to [orderedBookIds] — maps to
     * [com.calypsan.listenup.api.ReadingOrderService.reorderReadingOrder]. Last-write-wins, so the
     * outbox coalesces these.
     *
     * @property readingOrderId the order being rearranged.
     * @property orderedBookIds its books, first to last.
     */
    @Serializable
    @SerialName("ReadingOrderBookMutation.Reorder")
    data class Reorder(
        @SerialName("readingOrderId") val readingOrderId: String,
        @SerialName("orderedBookIds") val orderedBookIds: List<String>,
    ) : ReadingOrderBookMutation
}

/**
 * The offline-first outbox payload for the caller's choice of order on one series (#962), riding the
 * `reading_order_follows` channel keyed by the follow id. Both variants carry the whole terminal state
 * for that series, under one op kind, so the queue coalesces them — the [BookRatingMutation] precedent.
 */
@Serializable
sealed interface ReadingOrderFollowMutation {
    /** The series this mutation is about. */
    val seriesId: String

    /**
     * Follow [kind] (with [readingOrderId] for ORDER) on [seriesId] — maps to
     * [com.calypsan.listenup.api.ReadingOrderService.chooseReadingOrder].
     *
     * @property kind which kind of order; defaults to SERIES so an unknown kind coerces safely.
     * @property readingOrderId the followed user-made order; set iff [kind] is ORDER.
     */
    @Serializable
    @SerialName("ReadingOrderFollowMutation.Choose")
    data class Choose(
        @SerialName("seriesId") override val seriesId: String,
        @SerialName("kind") val kind: ReadingOrderChoiceKind = ReadingOrderChoiceKind.SERIES,
        @SerialName("readingOrderId") val readingOrderId: String? = null,
    ) : ReadingOrderFollowMutation

    /**
     * Clear the choice on [seriesId], so it inherits again — maps to
     * [com.calypsan.listenup.api.ReadingOrderService.clearReadingOrderChoice].
     */
    @Serializable
    @SerialName("ReadingOrderFollowMutation.Clear")
    data class Clear(
        @SerialName("seriesId") override val seriesId: String,
    ) : ReadingOrderFollowMutation
}
