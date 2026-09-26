package com.calypsan.listenup.client.presentation.chaptereditor.timeline

import com.calypsan.listenup.client.core.ChapterTimeFormat
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.retimed

/**
 * How much of the book the detail lane shows when the editor opens: ten minutes.
 *
 * Ten minutes rather than the whole book, because the whole book is exactly the view that does not
 * work: at 65 hours every boundary lands within a pixel or two of its neighbours and the lane can
 * neither be read nor aimed at. The minimap above it is what covers the whole book.
 */
const val DEFAULT_LANE_WINDOW_MS: Long = 600_000L

/** The narrowest the lane zooms: ten seconds, as [TimelineGeometry.zoomBy] allows. */
private const val MIN_ZOOM_WINDOW_MS = 10_000L

/**
 * The detail lane's interactive state: which slice of the book it shows, and the boundary being
 * dragged, if any. Immutable — every gesture returns the next lane.
 *
 * Shared by all three clients. Each translates its own gestures (touch drag and pinch on the
 * phones, pointer drag and wheel on the web) into these calls and draws what comes back, so the
 * behaviour a reader learns on one device is the behaviour on the others.
 *
 * **One drag is one edit.** A drag only *previews* ([preview]) while it moves; the caller commits
 * [committedStartMs] once, on release. Committing every movement made Undo after a drag walk back
 * a pixel at a time. The preview goes through the same `retimed` the commit does, so where a
 * boundary is drawn during the drag is exactly where it lands.
 *
 * @property geometry the visible window and the lane's measured width.
 * @property drag the boundary under the pointer, or null.
 */
data class TimelineLane(
    val geometry: TimelineGeometry,
    val drag: ScrubDrag? = null,
) {
    /** The lane at its real, measured width; the window is unchanged. */
    fun measured(widthPx: Float): TimelineLane = copy(geometry = geometry.copy(widthPx = widthPx))

    /**
     * Starts dragging the boundary under [xPx], if there is one and it is not locked. A locked
     * boundary is pinned against drift, and it stays pinned however it is moved.
     */
    fun grabbed(
        xPx: Float,
        markers: List<TimelineChapter>,
    ): TimelineLane {
        val grabbed = chapterGrabbedAt(xPx, markers, geometry)?.takeIf { !it.locked } ?: return copy(drag = null)
        return copy(drag = ScrubDrag(grabbed.id, grabbed.startMs))
    }

    /**
     * Folds one pointer movement into the drag: [dxPx] sideways since the last movement, [pulledDp]
     * away from where the drag began (the fine-scrub pull), [shiftHeld] for the desktop shortcut.
     */
    fun dragged(
        dxPx: Float,
        pulledDp: Float,
        shiftHeld: Boolean = false,
    ): TimelineLane {
        val current = drag ?: return this
        return copy(drag = current.advanced(dxPx, pulledDp, geometry.msPerPixel, shiftHeld))
    }

    /** Ends the drag. Read [committedStartMs] first. */
    fun released(): TimelineLane = copy(drag = null)

    /** [chapters] as they would be if the drag ended now — what the lane and the list draw. */
    fun preview(
        chapters: List<Chapter>,
        bookDurationMs: Long,
    ): List<Chapter> {
        val current = drag ?: return chapters
        return chapters.retimed(current.chapterId, current.targetMs, bookDurationMs)
    }

    /** Where the dragged boundary lands on release, clamped as the edit will clamp it; null when idle. */
    fun committedStartMs(
        chapters: List<Chapter>,
        bookDurationMs: Long,
    ): Long? {
        val current = drag ?: return null
        return preview(chapters, bookDurationMs).firstOrNull { it.id == current.chapterId }?.startTime
    }

    /**
     * True while the drag has pushed its boundary against a neighbour (or an end of the book) and is
     * being held there — the moment the phones answer with a haptic "resist" (spec §7.6).
     */
    fun isHeldByNeighbour(
        chapters: List<Chapter>,
        bookDurationMs: Long,
    ): Boolean {
        val current = drag ?: return false
        return committedStartMs(chapters, bookDurationMs) != current.targetMs
    }

    /**
     * The drag's readout — `25× · 0:00:50.04` — or null when idle. Both halves, because the pull is
     * only aimable if it says which step it landed in, and the number is what the reader is aiming at.
     */
    fun readout(
        chapters: List<Chapter>,
        bookDurationMs: Long,
    ): String? {
        val current = drag ?: return null
        val at = committedStartMs(chapters, bookDurationMs) ?: return null
        return "${current.step.label} · ${ChapterTimeFormat.exact(at)}"
    }

    /** Zooms by [factor] (below 1 narrows) around the instant under [focusPx], down to ten seconds. */
    fun zoomed(
        factor: Float,
        focusPx: Float,
        bookDurationMs: Long,
    ): TimelineLane = copy(geometry = geometry.zoomBy(factor, focusPx, bookDurationMs))

    /**
     * Zooms by [factor] around the middle of the window — the zoom buttons, which have no pointer
     * to zoom around and may run before the lane has been measured.
     */
    fun zoomedAroundCentre(
        factor: Float,
        bookDurationMs: Long,
    ): TimelineLane {
        val length =
            (geometry.windowLengthMs * factor.toDouble())
                .toLong()
                .coerceIn(MIN_ZOOM_WINDOW_MS, bookDurationMs.coerceAtLeast(MIN_ZOOM_WINDOW_MS))
        val centre = (geometry.windowStartMs + geometry.windowEndMs) / 2
        val bounded = length.coerceAtMost(bookDurationMs.coerceAtLeast(0L))
        val start = (centre - bounded / 2).coerceIn(0L, (bookDurationMs - bounded).coerceAtLeast(0L))
        return copy(geometry = geometry.copy(windowStartMs = start, windowEndMs = start + bounded))
    }

    /** Pans by [dxPx]; dragging the lane rightwards shows earlier audio. */
    fun panned(
        dxPx: Float,
        bookDurationMs: Long,
    ): TimelineLane = copy(geometry = geometry.panBy(dxPx, bookDurationMs))

    /** Moves the window so [ms] sits in its middle, keeping its length and staying inside the book. */
    fun centredOn(
        ms: Long,
        bookDurationMs: Long,
    ): TimelineLane {
        val length = geometry.windowLengthMs
        val start = (ms - length / 2).coerceIn(0L, (bookDurationMs - length).coerceAtLeast(0L))
        return copy(geometry = geometry.copy(windowStartMs = start, windowEndMs = start + length))
    }

    /** Factories. */
    companion object {
        /**
         * The lane as the editor opens: [DEFAULT_LANE_WINDOW_MS] around the listener's position when
         * this book is playing ([aroundMs]), else from the start. A book shorter than the window is
         * shown whole.
         */
        fun opening(
            bookDurationMs: Long,
            aroundMs: Long?,
            widthPx: Float,
        ): TimelineLane {
            val length = DEFAULT_LANE_WINDOW_MS.coerceAtMost(bookDurationMs.coerceAtLeast(0L))
            val lane = TimelineLane(TimelineGeometry(windowStartMs = 0L, windowEndMs = length, widthPx = widthPx))
            return if (aroundMs == null) lane else lane.centredOn(aroundMs, bookDurationMs)
        }
    }
}
