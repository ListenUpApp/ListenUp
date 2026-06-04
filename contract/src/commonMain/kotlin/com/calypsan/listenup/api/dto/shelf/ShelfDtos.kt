package com.calypsan.listenup.api.dto.shelf

import com.calypsan.listenup.core.ShelfId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Summary DTO for a single shelf, used in list views.
 *
 * Returned by [com.calypsan.listenup.api.ShelfService.listMyShelves] and embedded
 * inside [DiscoveredShelf]. Carries the shelf identity, display metadata, and a
 * live [bookCount] computed at query time.
 *
 * @property id Stable shelf identifier.
 * @property name Display name of the shelf (e.g. "To Read", "Favorites").
 * @property description Optional description of the shelf's theme or purpose.
 * @property isPrivate `true` if only the owner can see this shelf.
 * @property bookCount Number of non-deleted books currently in the shelf.
 * @property updatedAt Epoch millis of the last server-side write to this shelf.
 */
@Serializable
@SerialName("Shelf")
data class Shelf(
    @SerialName("id") val id: ShelfId,
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("isPrivate") val isPrivate: Boolean,
    @SerialName("bookCount") val bookCount: Int,
    @SerialName("updatedAt") val updatedAt: Long,
)

/**
 * Slim book view within a shelf detail response.
 *
 * Carries only the display-critical fields so the client can render the shelf
 * book list without a separate `BookService.getBook` call for each entry.
 *
 * @property bookId Stable book identifier.
 * @property title Display title of the book.
 * @property authors Ordered list of author display names.
 */
@Serializable
@SerialName("ShelfBookView")
data class ShelfBookView(
    @SerialName("bookId") val bookId: String,
    @SerialName("title") val title: String,
    @SerialName("authors") val authors: List<String>,
)

/**
 * Full shelf detail DTO, returned by [com.calypsan.listenup.api.ShelfService.getShelf].
 *
 * Contains the complete book list (access-filtered for non-owners) and aggregate
 * stats. [isOwner] lets the client adapt the UI between owner (edit) and viewer
 * (read-only) modes without a separate identity check.
 *
 * @property id Stable shelf identifier.
 * @property name Display name of the shelf.
 * @property description Optional description.
 * @property isPrivate `true` if only the owner can see this shelf.
 * @property isOwner `true` when the caller is the shelf owner.
 * @property books Ordered, access-filtered list of books in the shelf.
 * @property bookCount Total number of books visible to the caller.
 * @property totalDurationMs Sum of all visible books' audio duration in milliseconds.
 */
@Serializable
@SerialName("ShelfDetail")
data class ShelfDetail(
    @SerialName("id") val id: ShelfId,
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("isPrivate") val isPrivate: Boolean,
    @SerialName("isOwner") val isOwner: Boolean,
    @SerialName("books") val books: List<ShelfBookView>,
    @SerialName("bookCount") val bookCount: Int,
    @SerialName("totalDurationMs") val totalDurationMs: Long,
)

/**
 * A shelf discovered via [com.calypsan.listenup.api.ShelfService.discoverShelves].
 *
 * Wraps a [Shelf] summary with the owner's identity and display name so the
 * discovery screen can show "Sam's To Read" without a separate user lookup.
 * Only public shelves with at least one book accessible to the caller are returned.
 *
 * @property shelf The shelf summary (book count reflects only accessible books).
 * @property ownerId Stable user ID of the shelf owner.
 * @property ownerDisplayName Display name of the shelf owner for UI labeling.
 */
@Serializable
@SerialName("DiscoveredShelf")
data class DiscoveredShelf(
    @SerialName("shelf") val shelf: Shelf,
    @SerialName("ownerId") val ownerId: String,
    @SerialName("ownerDisplayName") val ownerDisplayName: String,
)
