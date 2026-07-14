package com.calypsan.listenup.server.metadata.spi

/**
 * What the enrich phase knows about a book *before* asking a provider to fill it in.
 *
 * This is the lookup key handed to every fetch capability. ASIN-keyed catalogs
 * (Audible, Audnexus) use [asin] directly when present; search-keyed lookups fall
 * back to [title] + [primaryAuthor]. [durationMs], when known, feeds the phase-1
 * match scorer so a candidate whose runtime matches the local file out-ranks a
 * same-title edition of a different length.
 *
 * All fields except [title] are nullable — a freshly scanned book may carry only a
 * folder-derived title.
 */
data class BookIdentity(
    /** Audible/Audnexus catalog key, when the scan or a prior match resolved one. */
    val asin: String? = null,
    /** ISBN when known — some search-keyed catalogs accept it. */
    val isbn: String? = null,
    /** The book's best-known title; always present (folder-derived at minimum). */
    val title: String,
    /** The primary author, used for search-keyed lookups when [asin] is absent. */
    val primaryAuthor: String? = null,
    /** Local audio runtime in milliseconds, when measured — feeds the match scorer. */
    val durationMs: Long? = null,
)
