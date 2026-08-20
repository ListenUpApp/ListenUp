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
    /**
     * The sequence AS TYPED, not as stored.
     *
     * Text, deliberately, while [BookSeries.sequence] is a `Double`: this is the edit screen's
     * in-flight value, and a half-typed "1." is not a number yet. Parsing on every keystroke would
     * round-trip that through null and back to "", and the text field treats a value it did not
     * emit as an external replacement — so the field would clear itself the moment the user pressed
     * the decimal point, making "1.5" impossible to type.
     *
     * It is parsed to a number once, on save, in `UpdateBookUseCase`.
     */
    val sequence: String? = null,
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
