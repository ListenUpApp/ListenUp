package com.calypsan.listenup.client.domain.model

/**
 * Lightweight representation of a contributor in the context of a specific book.
 *
 * Contains only the contributor's identity and their roles for the book (e.g., author, narrator).
 * For full contributor details (biography, image, etc.), see the [Contributor] domain model.
 */
data class BookContributor(
    val id: String,
    val name: String,
    val roles: List<String> = emptyList(),
)
