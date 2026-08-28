package com.calypsan.listenup.client.features.chaptereditor

import com.calypsan.listenup.client.design.timeline.FineScrubStep
import com.calypsan.listenup.client.design.timeline.TimelineChapter
import com.calypsan.listenup.client.design.timeline.TimelineGeometry
import com.calypsan.listenup.client.design.timeline.fineScrubStepFor
import kotlin.math.abs

/** How near the press has to land, in pixels, for a marker to be the one being grabbed. */
private const val GRAB_RADIUS_PX = 24f

/**
 * A boundary being dragged along the lane, and how far it has travelled so far.
 *
 * The offset **accumulates** rather than being recomputed from the drag's total displacement. That
 * distinction is the whole behaviour: pulling away from the lane must slow what happens *next*, not
 * retroactively rescale what already happened. Recomputing from the total would yank the marker
 * backwards the instant the user pulled away to be more careful — punishing exactly the gesture the
 * feature exists to reward.
 *
 * @property chapterId the boundary under the finger.
 * @property originalStartMs where it sat when the drag began, so the drag is always relative to a
 *   fixed point and repeated [advanced] calls cannot drift against a moving target.
 * @property accumulatedMs how far it has been moved, signed.
 * @property step the step the most recent movement was scaled by — what the HUD reports.
 */
data class ScrubDrag(
    val chapterId: String,
    val originalStartMs: Long,
    val accumulatedMs: Long = 0L,
    val step: FineScrubStep = FineScrubStep.Full,
) {
    /** Where the boundary would land right now. */
    val targetMs: Long get() = originalStartMs + accumulatedMs

    /**
     * Folds one pointer movement in.
     *
     * @param dragPx horizontal movement since the last event.
     * @param verticalDistanceDp how far the pointer has pulled away from where the drag began.
     * @param msPerPixel the lane's own scale — see [TimelineGeometry.msPerPixel].
     * @param shiftHeld the desktop shortcut for the finest step.
     */
    fun advanced(
        dragPx: Float,
        verticalDistanceDp: Float,
        msPerPixel: Double,
        shiftHeld: Boolean = false,
    ): ScrubDrag {
        val next = fineScrubStepFor(verticalDistanceDp, shiftHeld)
        return copy(
            accumulatedMs = accumulatedMs + next.timeDeltaMs(dragPx, msPerPixel),
            step = next,
        )
    }
}

/**
 * The marker a press at [xPx] is grabbing, or null if it did not land near one.
 *
 * Nearest-within-a-radius rather than nearest outright: a press in open lane is a press in open
 * lane, and silently grabbing the closest boundary half a screen away would move a chapter the
 * user never touched.
 */
fun chapterGrabbedAt(
    xPx: Float,
    chapters: List<TimelineChapter>,
    geometry: TimelineGeometry,
    grabRadiusPx: Float = GRAB_RADIUS_PX,
): TimelineChapter? =
    chapters
        .filter { abs(geometry.xOf(it.startMs) - xPx) <= grabRadiusPx }
        .minByOrNull { abs(geometry.xOf(it.startMs) - xPx) }
