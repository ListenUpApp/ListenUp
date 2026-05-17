package com.calypsan.listenup.client.domain.model

/**
 * Role types for contributors.
 *
 * Mirrors the server's contributor role set. This is a domain concept representing
 * the different roles a contributor can have in creating an audiobook.
 */
enum class ContributorRole(
    val apiValue: String,
) {
    AUTHOR("author"),
    NARRATOR("narrator"),
    EDITOR("editor"),
    TRANSLATOR("translator"),
    FOREWORD("foreword"),
    INTRODUCTION("introduction"),
    AFTERWORD("afterword"),
    PRODUCER("producer"),
    ADAPTER("adapter"),
    ILLUSTRATOR("illustrator"),
    ;

    companion object {
        fun fromApiValue(value: String): ContributorRole? =
            entries.find { it.apiValue.equals(value, ignoreCase = true) }
    }
}

/**
 * Contributor with roles for editing.
 *
 * Domain model representing a contributor that can be modified.
 * Used by [BookEditData] and related use cases.
 */
data class EditableContributor(
    val id: String? = null, // null for newly added contributors
    val name: String,
    val roles: Set<ContributorRole>,
)

/**
 * Series membership for editing.
 *
 * Domain model representing a book's membership in a series.
 */
data class EditableSeries(
    val id: String? = null, // null for newly added series
    val name: String,
    val sequence: String? = null, // e.g., "1", "1.5"
)

/**
 * Genre for editing.
 *
 * Domain model representing a genre assignment.
 * Path represents the hierarchical position (e.g., "/fiction/fantasy/epic-fantasy").
 */
data class EditableGenre(
    val id: String,
    val name: String,
    val path: String,
)

/**
 * Tag for editing.
 *
 * Tags are global community descriptors identified by slug.
 */
data class EditableTag(
    val id: String,
    val slug: String,
)
