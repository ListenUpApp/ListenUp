package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The offline-first outbox payload for a collection↔book junction edit, riding the `collection_books`
 * outbox channel keyed by the synthetic `"$collectionId:$bookId"` envelope id — the same id the
 * junction's mirror row uses — so the per-entity FIFO order and the anti-flicker shield align
 * with the row the edit changes. Mirrors [ShelfBookMutation].
 *
 * Unlike a book↔tag/mood junction, adding a book to a collection mints no new server id (the book
 * already exists), so both add and remove are modelled and both are offline-first.
 */
@Serializable
sealed interface CollectionBookMutation {
    /**
     * Add the book [bookId] to collection [collectionId] — maps to
     * [com.calypsan.listenup.api.CollectionService.addBookToCollection]. Idempotent server-side.
     *
     * @property collectionId the collection gaining the book.
     * @property bookId the book being added.
     */
    @Serializable
    @SerialName("CollectionBookMutation.Add")
    data class Add(
        @SerialName("collectionId") val collectionId: String,
        @SerialName("bookId") val bookId: String,
    ) : CollectionBookMutation

    /**
     * Remove the book [bookId] from collection [collectionId] — maps to
     * [com.calypsan.listenup.api.CollectionService.removeBookFromCollection]. Idempotent server-side.
     *
     * @property collectionId the collection losing the book.
     * @property bookId the book being removed.
     */
    @Serializable
    @SerialName("CollectionBookMutation.Remove")
    data class Remove(
        @SerialName("collectionId") val collectionId: String,
        @SerialName("bookId") val bookId: String,
    ) : CollectionBookMutation
}
