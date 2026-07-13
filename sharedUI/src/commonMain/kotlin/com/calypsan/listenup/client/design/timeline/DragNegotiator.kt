package com.calypsan.listenup.client.design.timeline

/**
 * Gesture-side state machine for dragging a [TimeMarker] within a [MarkerLane], composable-free so
 * it is exhaustively unit-testable. One instance is scoped to a single potential drag; the caller
 * (a `pointerInput` block in `MarkerLaneTimeline`) drives it through [beginDrag] → zero or more
 * [dragTo] → exactly one of [endDrag] / [cancelDrag].
 */
class DragNegotiator(
    private val policy: LanePolicy,
) {
    /** Result of a single [dragTo] call. */
    data class DragUpdate(
        val marker: TimeMarker,
        val ms: Long,
        /** True when [policy] clamped away from the raw proposed ms — the "resist at limit" signal. */
        val resisted: Boolean,
    )

    private var active: TimeMarker? = null
    private var committedThisDrag = false

    /** True while a drag is in progress (between [beginDrag] and [endDrag]/[cancelDrag]). */
    val isDragging: Boolean
        get() = active != null

    /**
     * Attempts to start a drag on [marker]. Returns `false` (and starts nothing) if
     * `policy.canDrag(marker)` is `false` — the caller's pointerInput should not enter a drag state.
     */
    fun beginDrag(marker: TimeMarker): Boolean {
        if (!policy.canDrag(marker)) return false
        active = marker
        committedThisDrag = false
        return true
    }

    /**
     * Routes [proposedMs] through `policy.clamp`. No-ops (returns `null`) if no drag is active.
     * May be called any number of times per drag.
     */
    fun dragTo(
        proposedMs: Long,
        siblings: List<TimeMarker>,
    ): DragUpdate? {
        val marker = active ?: return null
        val clamped = policy.clamp(marker, proposedMs, siblings)
        return DragUpdate(marker = marker, ms = clamped, resisted = clamped != proposedMs)
    }

    /**
     * Ends the active drag, firing `policy.onCommit(marker, newMs)` exactly once. No-ops if no drag
     * is active or if this negotiator already committed for the current drag (defensive against a
     * caller invoking [endDrag] twice for one gesture).
     */
    fun endDrag(newMs: Long) {
        val marker = active ?: return
        if (!committedThisDrag) {
            policy.onCommit(marker, newMs)
            committedThisDrag = true
        }
        active = null
    }

    /** Ends the active drag WITHOUT committing — `policy.onCommit` is never called. */
    fun cancelDrag() {
        active = null
        committedThisDrag = false
    }
}
