package com.calypsan.listenup.server.metadata.spi

/**
 * A series placement for a book, as a provider reports it.
 *
 * A book can sit in more than one series (a main arc and a publisher collection),
 * so [SeriesSource] returns a list. [key] is the catalog's stable series id when
 * exposed; [sequence] is the book's position kept as a string because catalogs use
 * non-integer positions (`"1.5"`, `"Book 2"`).
 */
data class SeriesMeta(
    /** Catalog series key, when available. */
    val key: String? = null,
    /** Series title. */
    val title: String,
    /** The book's position within the series, verbatim (`"1"`, `"1.5"`). */
    val sequence: String? = null,
)
