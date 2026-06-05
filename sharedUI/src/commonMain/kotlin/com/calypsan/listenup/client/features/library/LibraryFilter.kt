package com.calypsan.listenup.client.features.library

/**
 * The Library's content filters, surfaced as a coral chip row (replacing the old tab bar).
 *
 * [InProgress] is a view over the Books grid filtered to partially-played titles; the other four
 * map to the library's entity lists. (A `Genres` filter is intentionally absent until server-side
 * genre resolution is populated.)
 */
enum class LibraryFilter(
    val label: String,
) {
    Books("Books"),
    InProgress("In progress"),
    Series("Series"),
    Authors("Authors"),
    Narrators("Narrators"),
}
