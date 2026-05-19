package com.calypsan.listenup.client.domain.model

/**
 * Domain model for a genre.
 *
 * Genres are system-controlled categories for classifying audiobooks.
 * They form a hierarchy (e.g., Fiction > Fantasy > Epic Fantasy).
 * Users select from existing genres - they cannot create new ones.
 */
data class Genre(
    val id: String,
    val name: String, // Display name: "Epic Fantasy"
    val slug: String, // URL-safe key: "epic-fantasy"
    val path: String, // Materialized path: "/fiction/fantasy/epic-fantasy"
    val bookCount: Int = 0,
) {
    /**
     * Returns the parent path for display context.
     * "/fiction/fantasy/epic-fantasy" -> "Fiction > Fantasy"
     */
    val parentPath: String?
        get() {
            val segments = path.trim('/').split('/')
            if (segments.size <= 1) return null
            return segments
                .dropLast(1)
                .joinToString(" > ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
}
