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
 * Categories available for sorting, each with the direction it sensibly starts in.
 *
 * Carries no copy: each platform names a category and its directions from its own string catalog
 * (spec R12), so a label can be translated and title-cased where the platform wants it.
 */
enum class SortCategory(
    val defaultDirection: SortDirection,
) {
    // Text-based sorts (alphabetical)
    TITLE(SortDirection.ASCENDING),
    AUTHOR(SortDirection.ASCENDING),
    NAME(SortDirection.ASCENDING),

    // Numeric sorts
    DURATION(SortDirection.DESCENDING),
    YEAR(SortDirection.DESCENDING),
    BOOK_COUNT(SortDirection.DESCENDING),

    // Date sorts
    ADDED(SortDirection.DESCENDING),

    RATING(SortDirection.DESCENDING),
    LISTENER_RATING(SortDirection.DESCENDING),

    // Special sorts
    SERIES(SortDirection.ASCENDING),
    ;

    val key: String get() = name.lowercase()

    companion object {
        /** Categories available for the Books tab. */
        val booksCategories: List<SortCategory> =
            listOf(
                TITLE,
                AUTHOR,
                DURATION,
                YEAR,
                ADDED,
                RATING,
                LISTENER_RATING,
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
