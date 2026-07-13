package com.calypsan.listenup.client.features.chaptereditor

import com.calypsan.listenup.client.design.timeline.LanePolicy
import com.calypsan.listenup.client.design.timeline.TimeMarker
import com.calypsan.listenup.client.domain.model.AudioFile

/**
 * [LanePolicy] for the file-boundary lane on the chapter editor's timeline: a read-only reference
 * lane showing where the underlying audio files join, so the user can see chapter boundaries
 * against the original file layout without being able to move it. `canDrag` is always `false`;
 * [clamp] and [onCommit] are still implemented (total, per [LanePolicy]'s contract) but are never
 * reached in practice, since [clamp]/[onCommit] are only invoked by `DragNegotiator` after a drag
 * that `canDrag` already refused to start.
 */
internal class FileBoundaryPolicy(
    private val audioFiles: () -> List<AudioFile>,
) : LanePolicy {
    /**
     * One marker per join between consecutive audio files, in play order — the cumulative duration
     * of every file up to and including that point. The final cumulative sum (the book's own end,
     * not a join between two files) and the implicit boundary at `0` (the book's start, not a join
     * either) are both excluded, so an *n*-file book yields exactly *n - 1* markers.
     */
    fun markers(): List<TimeMarker> {
        val sorted = audioFiles().sortedBy { it.index }
        var cumulativeMs = 0L
        return sorted.dropLast(1).mapIndexed { index, file ->
            cumulativeMs += file.duration
            TimeMarker(
                id = "file-$index",
                timeMs = cumulativeMs,
                label = sorted[index + 1].filename,
                styleKey = "fileBoundary",
            )
        }
    }

    override fun canDrag(marker: TimeMarker): Boolean = false

    override fun clamp(
        marker: TimeMarker,
        proposedMs: Long,
        siblings: List<TimeMarker>,
    ): Long = proposedMs

    override fun onCommit(
        marker: TimeMarker,
        newMs: Long,
    ) = Unit
}
