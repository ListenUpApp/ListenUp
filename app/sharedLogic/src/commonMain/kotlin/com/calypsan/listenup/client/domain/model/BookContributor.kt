package com.calypsan.listenup.client.domain.model

/**
 * Lightweight representation of a contributor in the context of a specific book.
 *
 * Contains only the contributor's identity and their roles for the book (e.g., author, narrator).
 * For full contributor details (biography, image, etc.), see the [Contributor] domain model.
 *
 * [name] is the display string for this book — it already resolves to [creditedAs] when a per-book
 * alias is set, falling back to the canonical contributor name otherwise. [creditedAs] preserves the
 * raw per-book alias (or `null` when the book credits the contributor under their canonical name) so
 * the load → edit → save round-trip can carry it back to the server unchanged.
 */
data class BookContributor(
    val id: String,
    val name: String,
    val creditedAs: String? = null,
    val roles: List<String> = emptyList(),
)
