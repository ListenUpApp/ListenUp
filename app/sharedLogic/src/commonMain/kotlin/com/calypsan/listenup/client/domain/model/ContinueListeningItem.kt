package com.calypsan.listenup.client.domain.model

/**
 * One slot in the Home → Continue Listening shelf. Either the book is hydrated
 * ([Ready]) or it's still in-flight via the sync substrate ([Loading]).
 *
 * The Loading state surfaces during the initial sync window — playback_positions
 * arrive before the corresponding books, and silently dropping those rows made
 * the shelf appear half-empty. Showing a placeholder card keeps the shelf size
 * stable and signals "filling in" to the user. The Loading item collapses to
 * Ready in the next repository emission once the book arrives in Room.
 */
sealed interface ContinueListeningItem {
    /** Book id is always known — it comes from the position row. */
    val bookId: String

    /** Book is fully hydrated; UI renders the normal Continue Listening card. */
    data class Ready(
        override val bookId: String,
        val book: ContinueListeningBook,
    ) : ContinueListeningItem

    /** Book not yet in Room (sync in-flight); UI renders a skeleton card. */
    data class Loading(
        override val bookId: String,
    ) : ContinueListeningItem
}
