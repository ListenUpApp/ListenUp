package com.calypsan.listenup.web.features.seriesdetail

// The words every series surface uses for where a series sits and how much it holds — one spelling,
// so the Library card, a search hit and both pickers can never drift apart.

/** "1 book" vs "5 books" — en.json's `common.book_count` / `common.books_count`. */
internal fun seriesBookCount(count: Int): String = if (count == 1) "1 book" else "$count books"

/** "4 series · 23 books" — en.json's `series.count_books`. */
internal fun seriesAndBookCount(
    seriesCount: Int,
    bookCount: Int,
): String = "$seriesCount series · ${seriesBookCount(bookCount)}"

/** The separator between the names of a series path: "Cosmere › Mistborn". */
internal const val SERIES_PATH_SEPARATOR = " › "

/**
 * Where a series sits and how many books it holds: "in Cosmere › Mistborn · 8 books" (en.json's
 * `series.in_path`), or just "8 books" for a top-level series, whose place needs no saying.
 */
internal fun seriesPlaceLine(
    path: List<String>,
    bookCount: Int,
): String =
    if (path.isEmpty()) {
        seriesBookCount(bookCount)
    } else {
        "in ${path.joinToString(SERIES_PATH_SEPARATOR)} · ${seriesBookCount(bookCount)}"
    }
