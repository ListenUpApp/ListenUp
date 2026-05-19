package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.core.Timestamp

/**
 * Domain model for a global tag.
 *
 * Tags are community-wide content descriptors that any user can apply to books
 * they can access. Examples: "found-family", "slow-burn", "unreliable-narrator".
 *
 * The slug is the source of truth - there is no separate name field.
 * Use [displayName] to get a human-readable version for UI display.
 */
data class Tag(
    val id: String,
    val slug: String,
    val bookCount: Int = 0,
    val createdAt: Timestamp? = null,
) {
    /**
     * Converts the slug to a human-readable display name.
     *
     * Transformation: "found-family" -> "Found Family"
     *
     * @return The slug converted to title case with hyphens replaced by spaces
     */
    fun displayName(): String =
        slug
            .split("-")
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.titlecase() }
            }
}
