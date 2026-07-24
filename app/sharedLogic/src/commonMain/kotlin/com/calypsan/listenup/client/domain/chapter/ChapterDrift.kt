package com.calypsan.listenup.client.domain.chapter

import com.calypsan.listenup.client.domain.model.Chapter
import kotlin.math.roundToLong

/**
 * A user-pinned "true" start for one chapter, the input to drift correction.
 *
 * [chapterId] identifies the anchor chapter; [trueStartMs] is where the user
 * heard it actually begin (absolute ms from the start of the book).
 */
data class ChapterAnchor(
    val chapterId: String,
    val trueStartMs: Long,
)

/** Outcome of [correctDrift]: a recomputed contiguous chapter set, or a typed rejection. */
sealed interface DriftResult {
    /** Success — [chapters] is the corrected, contiguous, book-bounded set. */
    data class Corrected(
        val chapters: List<Chapter>,
    ) : DriftResult

    /** The correction could not be computed. */
    sealed interface Rejected : DriftResult {
        /** Fewer than one anchor, more than two, or an anchor referencing an unknown chapter. */
        data object BadAnchors : Rejected

        /** Two anchors imply a non-positive scale (would reverse chapter order). */
        data object InvertedAnchors : Rejected
    }
}

/**
 * Corrects chapter drift by an affine map on start times, defined by one or two
 * anchors, then rebuilds contiguity.
 *
 * - **One anchor** → constant shift `f(s) = s + (trueStart - anchorStart)`.
 * - **Two anchors** → `f(s) = a*s + b` with `a = (t2-t1)/(s2-s1)`, `b = t1 - a*s1`,
 *   correcting both a constant offset and accumulating drift. `a <= 0` is rejected.
 *
 * Locked chapters ([lockedIds]) keep their original start. After mapping, starts
 * are clamped to `[0, bookDurationMs]`, re-sorted, and durations recomputed so the
 * set is contiguous (each end = next start; last end = [bookDurationMs]).
 */
fun correctDrift(
    chapters: List<Chapter>,
    anchors: List<ChapterAnchor>,
    bookDurationMs: Long,
    lockedIds: Set<String> = emptySet(),
): DriftResult {
    if (chapters.isEmpty()) return DriftResult.Corrected(chapters)
    if (anchors.isEmpty() || anchors.size > 2) return DriftResult.Rejected.BadAnchors

    val byId = chapters.associateBy { it.id }
    val a0 = anchors[0]
    val s1 = byId[a0.chapterId]?.startTime ?: return DriftResult.Rejected.BadAnchors

    val map: (Long) -> Long =
        if (anchors.size == 1) {
            val shift = a0.trueStartMs - s1
            { s -> s + shift }
        } else {
            val a1 = anchors[1]
            val s2 = byId[a1.chapterId]?.startTime ?: return DriftResult.Rejected.BadAnchors
            if (s2 == s1) return DriftResult.Rejected.BadAnchors
            val scale = (a1.trueStartMs - a0.trueStartMs).toDouble() / (s2 - s1).toDouble()
            if (scale <= 0.0) return DriftResult.Rejected.InvertedAnchors
            val offset = a0.trueStartMs - scale * s1
            { s -> (scale * s + offset).roundToLong() }
        }

    val moved =
        chapters
            .map { ch ->
                val newStart = if (ch.id in lockedIds) ch.startTime else map(ch.startTime)
                ch to newStart.coerceIn(0L, bookDurationMs)
            }.sortedBy { it.second }

    val result =
        moved.mapIndexed { i, (ch, start) ->
            val end = if (i == moved.lastIndex) bookDurationMs else moved[i + 1].second
            ch.copy(startTime = start, duration = (end - start).coerceAtLeast(0L))
        }
    return DriftResult.Corrected(result)
}
