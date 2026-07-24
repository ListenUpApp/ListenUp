package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import kotlin.time.Duration.Companion.seconds

/**
 * Domain model representing full shelf details for the shelf detail screen.
 *
 * Contains all information needed to display a shelf's detail view,
 * including ownership/privacy flags, stats, and the list of books.
 *
 * For non-owners, [books] is already access-filtered by the server — books the
 * viewer cannot access are silently excluded, so [bookCount] reflects only the
 * visible set.
 *
 * @property id Unique shelf identifier (typed [ShelfId])
 * @property name Shelf display name
 * @property description Optional shelf description
 * @property isPrivate `true` if only the owner can see this shelf
 * @property isOwner `true` when the current user owns this shelf (gates edit/delete/reorder)
 * @property bookCount Number of books visible to the viewer
 * @property totalDurationSeconds Total duration of all visible books in seconds
 * @property books Ordered list of books in the shelf
 */
data class ShelfDetail(
    val id: ShelfId,
    val name: String,
    val description: String?,
    val isPrivate: Boolean,
    val isOwner: Boolean,
    val bookCount: Int,
    val totalDurationSeconds: Long,
    val books: List<ShelfBook>,
) {
    /** The shelf id as a plain String, for the Swift/SKIE boundary (the value class is unboxed there). */
    val idString: String get() = id.value

    /**
     * Returns the total duration formatted as hours and minutes.
     */
    val formattedDuration: String
        get() = DurationFormatter.hoursMinutesCompact(totalDurationSeconds.seconds)
}

/**
 * A book within a shelf.
 *
 * The server's shelf book view supplies only display-critical fields; per-book
 * duration is not part of the contract, so it is omitted here. [coverPath] is
 * resolved client-side from the local image cache when available.
 *
 * @property id Book's unique identifier (typed [BookId])
 * @property title Book title
 * @property authorNames List of author names for this book
 * @property coverPath Local path to cover image (optional)
 * @property coverHash Content hash of the book's cover, used to bust the image cache when the
 *   cover changes (optional; resolved from the local `books` mirror)
 */
data class ShelfBook(
    val id: BookId,
    val title: String,
    val authorNames: List<String>,
    val coverPath: String?,
    val coverHash: String? = null,
) {
    /** The book id as a plain String, for the Swift/SKIE boundary (the value class is unboxed there). */
    val idString: String get() = id.value
}
