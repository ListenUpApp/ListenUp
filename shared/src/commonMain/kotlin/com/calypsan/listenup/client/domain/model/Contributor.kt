package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.core.ContributorId

/**
 * Domain model representing a book contributor (author, narrator, etc).
 *
 * Contributors are the people who create audiobooks - authors, narrators,
 * translators, etc. A single contributor can have multiple roles across
 * different books.
 *
 * This is the full contributor profile with biographical information.
 * For lightweight contributor references within a book context (with roles),
 * see [BookContributor].
 */
data class Contributor(
    val id: ContributorId,
    val name: String,
    val description: String? = null,
    val imagePath: String? = null,
    val imageBlurHash: String? = null,
    val website: String? = null,
    val birthDate: String? = null, // ISO 8601 date (e.g., "1947-09-21")
    val deathDate: String? = null, // ISO 8601 date (e.g., "2024-01-15")
    val aliases: List<String> = emptyList(),
) {
    /**
     * Check if a name matches this contributor (either primary name or alias).
     *
     * @param searchName The name to match against
     * @return true if the name matches the contributor's name or any alias
     */
    fun matchesName(searchName: String): Boolean =
        name.equals(searchName, ignoreCase = true) ||
            aliases.any { it.equals(searchName, ignoreCase = true) }
}
