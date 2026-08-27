package com.calypsan.listenup.client.domain.model

/**
 * A book's own vocabulary for its two chapter-grouping tiers.
 *
 * "Part"/"Book" for a conventional novel; "Sequence"/"Era" for a work that names its structure
 * differently. Either is null when that tier is unnamed — the UI then shows each group's own title
 * with no type chip, rather than inventing a word the author never used. The client read model for
 * [com.calypsan.listenup.api.sync.BookSyncPayload.bookTierLabel] /
 * [com.calypsan.listenup.api.sync.BookSyncPayload.partTierLabel]; the bound on a name's length is
 * shared with the server as [com.calypsan.listenup.domain.TierLabelLimits].
 */
data class TierLabels(
    val bookTierLabel: String? = null,
    val partTierLabel: String? = null,
) {
    /** True when the book names neither tier — the flat, overwhelmingly common case. */
    val isUnnamed: Boolean get() = bookTierLabel == null && partTierLabel == null

    companion object {
        /** A book that names neither tier. */
        val None = TierLabels()
    }
}
