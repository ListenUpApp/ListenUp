package com.calypsan.listenup.client.domain.model

/**
 * Role types for contributors. The shared vocabulary now lives in `:contract`
 * ([com.calypsan.listenup.api.dto.ContributorRole]); this alias preserves the
 * `domain.model.ContributorRole` import path used across the client.
 */
typealias ContributorRole = com.calypsan.listenup.api.dto.ContributorRole

/**
 * Contributor with roles for editing.
 *
 * Domain model representing a contributor that can be modified.
 * Used by [BookEditData] and related use cases.
 *
 * [creditedAs] preserves the per-book alias loaded from the book↔contributor join so an unedited
 * contributor is saved back under the same credited name instead of reverting to its canonical name.
 * `null` means the book credits the contributor under their canonical name.
 */
data class EditableContributor(
    val id: String? = null, // null for newly added contributors
    val name: String,
    val roles: Set<ContributorRole>,
    val creditedAs: String? = null,
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

/**
 * Mood for editing.
 *
 * Moods are global affective descriptors identified by slug.
 */
data class EditableMood(
    val id: String,
    val slug: String,
)

/**
 * Collection membership for editing.
 *
 * Domain model representing a book's membership in an admin-owned collection.
 * Collections are not auto-created from book-edit — [id] always references an
 * existing collection chosen from the available list.
 */
data class EditableCollection(
    val id: String,
    val name: String,
)
