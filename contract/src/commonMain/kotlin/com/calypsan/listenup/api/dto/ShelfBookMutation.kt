package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The offline-first outbox payload for a shelf↔book junction edit, riding the `shelf_books`
 * outbox channel keyed by the synthetic `"$shelfId:$bookId"` envelope id — the same id the
 * junction's mirror row uses — so the per-entity FIFO order and the anti-flicker shield align
 * with the row the edit changes. Mirrors [CollectionBookMutation].
 *
 * Unlike a book↔tag/mood junction, adding a book to a shelf mints no new server id (the book
 * already exists), so both add and remove are modelled and both are offline-first.
 */
@Serializable
sealed interface ShelfBookMutation {
    /**
     * Add the book [bookId] to shelf [shelfId] — maps to
     * [com.calypsan.listenup.api.ShelfService.addBookToShelf]. Idempotent server-side; the book is
     * appended at the end of the current sort order.
     *
     * @property shelfId the shelf gaining the book.
     * @property bookId the book being added.
     */
    @Serializable
    @SerialName("ShelfBookMutation.Add")
    data class Add(
        @SerialName("shelfId") val shelfId: String,
        @SerialName("bookId") val bookId: String,
    ) : ShelfBookMutation

    /**
     * Remove the book [bookId] from shelf [shelfId] — maps to
     * [com.calypsan.listenup.api.ShelfService.removeBookFromShelf]. Idempotent server-side.
     *
     * @property shelfId the shelf losing the book.
     * @property bookId the book being removed.
     */
    @Serializable
    @SerialName("ShelfBookMutation.Remove")
    data class Remove(
        @SerialName("shelfId") val shelfId: String,
        @SerialName("bookId") val bookId: String,
    ) : ShelfBookMutation

    /**
     * Set the shelf's whole order to [orderedBookIds].
     *
     * A permutation rather than a per-book move, because that is what the server's
     * `reorderShelfBooks` takes and what makes the op idempotent: re-firing it writes the same
     * indices. Last-write-wins by nature, which is why the outbox coalesces these.
     *
     * The server is tolerant of a list that has drifted from the shelf — ids no longer on it are
     * skipped, and books on the shelf but absent from the list are left where they are. That
     * matters offline: this op is keyed by shelf while add and remove are keyed by junction, so a
     * reorder can reach the server either side of an add or a removal it was composed against.
     */
    @Serializable
    @SerialName("ShelfBookMutation.Reorder")
    data class Reorder(
        @SerialName("shelfId") val shelfId: String,
        @SerialName("orderedBookIds") val orderedBookIds: List<String>,
    ) : ShelfBookMutation
}
