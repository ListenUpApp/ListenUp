package com.calypsan.listenup.server.metadata.spi

/**
 * A single ranked candidate returned by a [BookIdentitySource.searchBooks] query —
 * the phase-1 "which catalog entry is this local book?" answer.
 *
 * [score] is a 0.0..1.0 confidence the candidate matches the queried book, set by
 * [MatchScorer] (`0.55·duration + 0.2·title + 0.1·author + 0.15·narrator`) — runtime
 * dominates because two editions of the same title diverge most reliably by length.
 * Providers fill [score] with a provisional value the scorer replaces.
 */
data class BookMatch(
    /** Catalog key (ASIN) when the source is ASIN-keyed; `null` for keyless hits. */
    val asin: String? = null,
    /** Candidate title as the catalog lists it. */
    val title: String,
    /** Candidate primary author, when the catalog reports one. */
    val author: String? = null,
    /** Candidate runtime in milliseconds — the strongest match signal. */
    val durationMs: Long? = null,
    /** A cover thumbnail URL for disambiguation UIs, when available. */
    val coverUrl: String? = null,
    /** Match confidence in `0.0..1.0`; higher ranks first. */
    val score: Double,
    /** The candidate's narrators, when the catalogue lists them. */
    val narrators: List<String> = emptyList(),
)
