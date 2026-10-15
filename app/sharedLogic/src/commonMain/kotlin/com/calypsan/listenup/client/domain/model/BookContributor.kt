package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.image.ImageFrame

/**
 * Lightweight representation of a contributor in the context of a specific book.
 *
 * Contains only the contributor's identity and their roles for the book (e.g., author, narrator).
 * For full contributor details (biography, etc.), see the [Contributor] domain model. [imagePath] and [imageFrame]
 * are here so a credit row can show the contributor's framed photo without another lookup.
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
    val imagePath: String? = null,
    val imageFrame: ImageFrame? = null,
)
