package com.calypsan.listenup.client.domain.chapter

import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.core.AnchorValidation
import com.calypsan.listenup.core.TimeAnchor
import com.calypsan.listenup.core.alignTimestamps
import com.calypsan.listenup.core.validateAnchors

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
        /** Fewer than one anchor, or an anchor referencing an unknown chapter id. */
        data object BadAnchors : Rejected

        /** The anchors, sorted by chapter start, do not strictly increase in true-start too. */
        data object InvertedAnchors : Rejected
    }
}

/**
 * Corrects chapter drift via [alignTimestamps]'s N-anchor piecewise-linear map, then
 * rebuilds contiguity. One anchor is a constant shift; two or more interpolate/extrapolate
 * through every segment. Locked chapters ([lockedIds]) keep their original start. After
 * mapping, starts are clamped to `[0, bookDurationMs]`, re-sorted, and durations recomputed
 * so the set stays contiguous (each end = next start; last end = [bookDurationMs]).
 */
fun correctDrift(
    chapters: List<Chapter>,
    anchors: List<ChapterAnchor>,
    bookDurationMs: Long,
    lockedIds: Set<String> = emptySet(),
): DriftResult {
    if (chapters.isEmpty()) return DriftResult.Corrected(chapters)
    if (anchors.isEmpty()) return DriftResult.Rejected.BadAnchors

    val byId = chapters.associateBy { it.id }
    val timeAnchors = mutableListOf<TimeAnchor>()
    for (a in anchors) {
        val sourceMs = byId[a.chapterId]?.startTime ?: return DriftResult.Rejected.BadAnchors
        timeAnchors += TimeAnchor(sourceMs = sourceMs, targetMs = a.trueStartMs)
    }

    when (validateAnchors(timeAnchors)) {
        AnchorValidation.Valid -> Unit
        AnchorValidation.NoAnchors -> return DriftResult.Rejected.BadAnchors
        is AnchorValidation.InvertedSegment -> return DriftResult.Rejected.InvertedAnchors
    }

    val movedStarts = alignTimestamps(timeAnchors, chapters.map { it.startTime })
    val moved =
        chapters
            .mapIndexed { i, ch ->
                val newStart = if (ch.id in lockedIds) ch.startTime else movedStarts[i]
                ch to newStart.coerceIn(0L, bookDurationMs)
            }.sortedBy { it.second }

    val result =
        moved.mapIndexed { i, (ch, start) ->
            val end = if (i == moved.lastIndex) bookDurationMs else moved[i + 1].second
            ch.copy(startTime = start, duration = (end - start).coerceAtLeast(0L))
        }
    return DriftResult.Corrected(result)
}
