package com.calypsan.listenup.domain

/**
 * A book's own vocabulary for its two chapter-grouping tiers — e.g. "Part"/"Book" for a
 * conventional novel, or "Sequence"/"Era" for a work that names its structure differently.
 * Either field is null when that tier is unnamed (the UI then shows the chapter's grouping
 * value with no type chip, or offers to name it). Mirrors
 * [com.calypsan.listenup.api.sync.BookSyncPayload.bookTierLabel] /
 * [com.calypsan.listenup.api.sync.BookSyncPayload.partTierLabel] as the client's read model.
 */
data class TierLabels(
    val bookTierLabel: String?,
    val partTierLabel: String?,
)

/** Maximum accepted character length for a single tier-vocabulary label (e.g. "Part", "Sequence"). */
const val MAX_TIER_LABEL = 64
