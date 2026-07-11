package com.calypsan.listenup.core

import kotlin.math.roundToLong

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

/**
 * Maps [timestamps] through the piecewise-linear alignment defined by [anchors]
 * (any order; sorted by source internally). Between adjacent anchors the offset
 * interpolates linearly; outside the anchored span the nearest segment's slope
 * extrapolates. One anchor degenerates to a constant shift; two to the affine
 * drift map in the chapter-editing spec §6.
 *
 * Output preserves input order (result[i] corresponds to timestamps[i]).
 * Bounds clamping and chapter contiguity are caller concerns — this is only the map.
 *
 * @throws IllegalArgumentException if [validateAnchors] does not return
 *   [AnchorValidation.Valid]. Callers validate first; the throw guards misuse.
 */
fun alignTimestamps(anchors: List<TimeAnchor>, timestamps: List<Long>): List<Long> {
    require(validateAnchors(anchors) == AnchorValidation.Valid) {
        "alignTimestamps requires anchors that pass validateAnchors"
    }
    val sorted = anchors.sortedBy { it.sourceMs }
    if (sorted.size == 1) {
        val shift = sorted[0].targetMs - sorted[0].sourceMs
        return timestamps.map { it + shift }
    }
    return timestamps.map { ts -> mapThroughSegments(sorted, ts) }
}

private fun mapThroughSegments(sorted: List<TimeAnchor>, ts: Long): Long {
    // Pick the governing segment: the one containing ts, or the nearest edge
    // segment when ts lies outside the anchored span (slope extrapolation).
    val segmentStart = when {
        ts <= sorted.first().sourceMs -> 0
        ts >= sorted.last().sourceMs -> sorted.size - 2
        else -> sorted.indexOfLast { it.sourceMs <= ts }.coerceAtMost(sorted.size - 2)
    }
    val from = sorted[segmentStart]
    val to = sorted[segmentStart + 1]
    val slope = (to.targetMs - from.targetMs).toDouble() / (to.sourceMs - from.sourceMs).toDouble()
    return (from.targetMs + (ts - from.sourceMs) * slope).roundToLong()
}
