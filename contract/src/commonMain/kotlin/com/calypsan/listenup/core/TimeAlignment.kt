package com.calypsan.listenup.core

/**
 * Pins a source timestamp to its known-true target on the same clock.
 * The implied correction at this point is `targetMs - sourceMs`.
 *
 * Used by chapter drift correction (user pins true starts by ear), world-track
 * import (matched chapter pairs), and audio-changed re-anchoring.
 */
data class TimeAnchor(val sourceMs: Long, val targetMs: Long)

/** Result of [validateAnchors]: either usable for [alignTimestamps], or why not. */
sealed interface AnchorValidation {
    /** Anchors define a strictly increasing piecewise map. */
    data object Valid : AnchorValidation

    /** No anchors were supplied; at least one is required. */
    data object NoAnchors : AnchorValidation

    /**
     * After sorting by source, segment [segmentIndex] (between [from] and [to])
     * is not strictly increasing in both source and target — a mis-set anchor,
     * not a valid drift. UIs should name the offending pair to the user.
     */
    data class InvertedSegment(
        val segmentIndex: Int,
        val from: TimeAnchor,
        val to: TimeAnchor,
    ) : AnchorValidation
}

/**
 * Validates that [anchors] (any order; sorted by [TimeAnchor.sourceMs] internally)
 * define a strictly increasing piecewise-linear map: every adjacent pair must
 * strictly increase in both source and target. A single anchor is always valid
 * (it defines a constant shift).
 */
fun validateAnchors(anchors: List<TimeAnchor>): AnchorValidation {
    if (anchors.isEmpty()) return AnchorValidation.NoAnchors
    val sorted = anchors.sortedBy { it.sourceMs }
    for (i in 0 until sorted.size - 1) {
        val from = sorted[i]
        val to = sorted[i + 1]
        if (to.sourceMs <= from.sourceMs || to.targetMs <= from.targetMs) {
            return AnchorValidation.InvertedSegment(segmentIndex = i, from = from, to = to)
        }
    }
    return AnchorValidation.Valid
}
