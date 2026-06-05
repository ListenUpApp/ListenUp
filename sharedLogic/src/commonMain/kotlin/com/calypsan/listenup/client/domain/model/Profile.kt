package com.calypsan.listenup.client.domain.model

/**
 * A recently listened book shown on a user's profile.
 *
 * @property bookId Book's unique identifier
 * @property title Book title
 * @property coverPath Local path to cover image (optional)
 */
data class ProfileRecentBook(
    val bookId: String,
    val title: String,
    val coverPath: String?,
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
