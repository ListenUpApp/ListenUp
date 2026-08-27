package com.calypsan.listenup.domain

/**
 * Bound on a book's chapter-grouping tier names, shared by every enforcement point: the server's
 * `setBookTierLabels` validation and the client's editor before it ever sends. Single-sourced here
 * because the server cannot depend on client modules.
 *
 * A tier name is a word or two — "Part", "Volume", "Sequence" — not prose. The ceiling exists to
 * keep a stray paste out of the database, not to express a design opinion about naming.
 */
object TierLabelLimits {
    /** Maximum accepted length, in characters, of a single tier name. */
    const val MAX_LENGTH: Int = 64
}
