package com.calypsan.listenup.client.domain.model

/**
 * Domain model for an admin-managed collection.
 *
 * Collections are admin-only organizational groups for books.
 * Used for library organization and featured content sections.
 *
 * @property id Unique identifier
 * @property name Display name
 * @property bookCount Number of books in this collection
 * @property createdAtMs Creation timestamp
 * @property updatedAtMs Last update timestamp
 */
data class Collection(
    val id: String,
    val name: String,
    val bookCount: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)
