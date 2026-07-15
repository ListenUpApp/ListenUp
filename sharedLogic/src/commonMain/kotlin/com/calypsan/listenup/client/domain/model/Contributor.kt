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
    val website: String? = null,
    val birthDate: String? = null, // ISO 8601 date (e.g., "1947-09-21")
    val deathDate: String? = null, // ISO 8601 date (e.g., "2024-01-15")
    val aliases: List<String> = emptyList(),
    /** Alternate sort key for the contributor (e.g., "King, Stephen"). */
    val sortName: String? = null,
    /** Audible ASIN for this contributor, set when metadata has been applied. */
    val asin: String? = null,
) {
    /** The contributor id as a plain String, for the Swift/SKIE boundary (the value class is unboxed there). */
    val idString: String get() = id.value

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
