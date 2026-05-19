@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.presentation.library

/**
 * Direction of a sort operation.
 */
enum class SortDirection {
    ASCENDING,
    DESCENDING,
    ;

    val key: String get() = name.lowercase()

    fun toggle(): SortDirection =
        when (this) {
            ASCENDING -> DESCENDING
            DESCENDING -> ASCENDING
        }

    companion object {
        fun fromKey(key: String): SortDirection? = entries.find { it.key == key }
    }
}

/**
 * Categories available for sorting.
 *
 * Each category defines how it labels itself in ascending and descending order,
 * and what the sensible default direction is.
 */
enum class SortCategory(
    val label: String,
    val ascLabel: String,
    val descLabel: String,
    val defaultDirection: SortDirection,
) {
    // Text-based sorts (alphabetical)
    TITLE(
        label = "Title",
        ascLabel = "A \u2192 Z",
        descLabel = "Z \u2192 A",
        defaultDirection = SortDirection.ASCENDING,
    ),
    AUTHOR(
        label = "Author",
        ascLabel = "A \u2192 Z",
        descLabel = "Z \u2192 A",
        defaultDirection = SortDirection.ASCENDING,
    ),
    NAME(
        label = "Name",
        ascLabel = "A \u2192 Z",
        descLabel = "Z \u2192 A",
        defaultDirection = SortDirection.ASCENDING,
    ),

    // Numeric sorts
    DURATION(
        label = "Duration",
        ascLabel = "Shortest",
        descLabel = "Longest",
        defaultDirection = SortDirection.DESCENDING,
    ),
    YEAR(
        label = "Year",
        ascLabel = "Oldest",
        descLabel = "Newest",
        defaultDirection = SortDirection.DESCENDING,
    ),
    BOOK_COUNT(
        label = "Books",
        ascLabel = "Fewest",
        descLabel = "Most",
        defaultDirection = SortDirection.DESCENDING,
    ),

    // Date sorts
    ADDED(
        label = "Added",
        ascLabel = "First",
        descLabel = "Recent",
        defaultDirection = SortDirection.DESCENDING,
    ),

    // Special sorts
    SERIES(
        label = "Series",
        ascLabel = "1 \u2192 N",
        descLabel = "N \u2192 1",
        defaultDirection = SortDirection.ASCENDING,
    ),
    ;

    val key: String get() = name.lowercase()

    /**
     * Get the direction label for a given direction.
     */
    fun directionLabel(direction: SortDirection): String =
        when (direction) {
            SortDirection.ASCENDING -> ascLabel
            SortDirection.DESCENDING -> descLabel
        }

    companion object {
        /** Categories available for the Books tab. */
        val booksCategories: List<SortCategory> =
            listOf(
                TITLE,
                AUTHOR,
                DURATION,
                YEAR,
                ADDED,
                SERIES,
            )

        /** Categories available for the Series tab. */
        val seriesCategories: List<SortCategory> =
            listOf(
                NAME,
                BOOK_COUNT,
                ADDED,
            )

        /** Categories available for Authors/Narrators tabs. */
        val contributorCategories: List<SortCategory> =
            listOf(
                NAME,
                BOOK_COUNT,
            )

        fun fromKey(key: String): SortCategory? = entries.find { it.key == key }
    }
}

/**
 * Complete sort state: what to sort by and in what direction.
 *
 * This is the single source of truth for how a list should be sorted.
 */
data class SortState(
    val category: SortCategory,
    val direction: SortDirection,
) {
    /**
     * The direction label for display (e.g., "A → Z", "Longest", "Recent").
     */
    val directionLabel: String get() = category.directionLabel(direction)

    /**
     * Persistence key combining category and direction.
     */
    val persistenceKey: String get() = "${category.key}:${direction.key}"

    /**
     * Toggle the direction while keeping the same category.
     */
    fun toggleDirection(): SortState = copy(direction = direction.toggle())

    /**
     * Change the category, using the new category's default direction.
     */
    fun withCategory(newCategory: SortCategory): SortState =
        SortState(
            category = newCategory,
            direction = newCategory.defaultDirection,
        )

    companion object {
        /** Default sort state for Books tab. */
        val booksDefault = SortState(SortCategory.TITLE, SortDirection.ASCENDING)

        /** Default sort state for Series tab. */
        val seriesDefault = SortState(SortCategory.NAME, SortDirection.ASCENDING)

        /** Default sort state for Authors/Narrators tabs. */
        val contributorDefault = SortState(SortCategory.NAME, SortDirection.ASCENDING)

        /**
         * Parse a sort state from its persistence key.
         */
        fun fromPersistenceKey(key: String): SortState? {
            val parts = key.split(":")
            if (parts.size != 2) return null
            val category = SortCategory.fromKey(parts[0]) ?: return null
            val direction = SortDirection.fromKey(parts[1]) ?: return null
            return SortState(category, direction)
        }
    }
}
