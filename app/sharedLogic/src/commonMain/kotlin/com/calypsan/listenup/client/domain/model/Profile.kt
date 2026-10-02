package com.calypsan.listenup.client.domain.model

/**
 * A book someone recently listened to, shown in their profile's "Recently listened" strip.
 *
 * Drawn from the synced activity feed, so it only ever names a book the viewer can open.
 *
 * @property bookId Book's unique identifier; covers resolve from it on every platform
 * @property title Book title
 * @property coverHash Content hash of the book's current cover, so a re-covered book busts its
 *   cached art; null when the book has no cover
 */
data class ProfileRecentBook(
    val bookId: String,
    val title: String,
    val coverHash: String?,
)

/**
 * Summary of a shelf shown on a user's profile.
 *
 * @property id Shelf unique identifier
 * @property name Shelf display name
 * @property bookCount Number of books in the shelf
 */
data class ProfileShelfSummary(
    val id: String,
    val name: String,
    val bookCount: Int,
)
