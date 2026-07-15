package com.calypsan.listenup.client.features.chaptereditor

import com.calypsan.listenup.client.design.timeline.LanePolicy
import com.calypsan.listenup.client.design.timeline.TimeMarker
import com.calypsan.listenup.client.domain.model.Chapter

/**
 * The minimum legal chapter duration, in milliseconds. Mirrors the floor
 * `ChapterEditorViewModel` enforces server-of-truth-side for new chapter boundaries — duplicated
 * here (rather than imported) because it is `private` in `:sharedLogic` and the two modules don't
 * share a constants surface for this value.
 */
private const val MIN_CHAPTER_DURATION_MS = 50L * 1000

/**
 * [LanePolicy] for the chapter-boundary lane on the chapter editor's timeline: one [TimeMarker] per
 * [Chapter], keyed by [Chapter.id] and positioned at [Chapter.startTime]. The book's very first
 * chapter is pinned at `0` and can never be dragged; every later chapter may be dragged but is
 * clamped to stay at least [MIN_CHAPTER_DURATION_MS] away from its immediate neighbors on either
 * side, so a drag can never squeeze a chapter below the minimum playable duration.
 */
internal class ChapterBoundaryPolicy(
    private val chapters: () -> List<Chapter>,
    private val onRetime: (chapterId: String, newMs: Long) -> Unit,
) : LanePolicy {
    override fun canDrag(marker: TimeMarker): Boolean = chapters().firstOrNull()?.id != marker.id

    override fun clamp(
        marker: TimeMarker,
        proposedMs: Long,
        siblings: List<TimeMarker>,
    ): Long {
        val lowerBound =
            siblings
                .filter { it.timeMs < marker.timeMs }
                .maxOfOrNull { it.timeMs }
                ?.let { it + MIN_CHAPTER_DURATION_MS }
                ?: 0L
        val upperBound =
            siblings
                .filter { it.timeMs > marker.timeMs }
                .minOfOrNull { it.timeMs }
                ?.let { it - MIN_CHAPTER_DURATION_MS }
                ?: Long.MAX_VALUE
        return proposedMs.coerceIn(lowerBound, upperBound.coerceAtLeast(lowerBound))
    }

    override fun onCommit(
        marker: TimeMarker,
        newMs: Long,
    ) = onRetime(marker.id, newMs)
}
