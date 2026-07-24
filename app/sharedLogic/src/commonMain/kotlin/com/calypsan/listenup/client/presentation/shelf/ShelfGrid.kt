package com.calypsan.listenup.client.presentation.shelf

/** Width buckets that drive the Shelf-detail cover-grid column count. */
enum class ShelfGridWidth { Compact, Medium, Expanded }

private const val COMPACT_COLUMNS = 2
private const val MEDIUM_COLUMNS = 4
private const val EXPANDED_COLUMNS = 6

/** Number of cover-grid columns for a given screen-width [width] bucket. */
fun shelfGridColumns(width: ShelfGridWidth): Int =
    when (width) {
        ShelfGridWidth.Compact -> COMPACT_COLUMNS
        ShelfGridWidth.Medium -> MEDIUM_COLUMNS
        ShelfGridWidth.Expanded -> EXPANDED_COLUMNS
    }
