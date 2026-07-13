package com.calypsan.listenup.client.design.timeline

import kotlinx.coroutines.flow.Flow

/**
 * One marker on a `MarkerLaneTimeline`'s shared time axis. [label] is optional display text (the
 * component never requires one — a marker can be a bare tick). [styleKey] selects a caller-supplied
 * `MarkerStyle` from the `styles` map passed to `MarkerLaneTimeline`; it names a *visual*, never a
 * behavior — behavior lives entirely in the owning lane's [LanePolicy].
 */
data class TimeMarker(
    val id: String,
    val timeMs: Long,
    val label: String?,
    val styleKey: String,
)

/**
 * One horizontal lane of markers on a `MarkerLaneTimeline`. The component renders lanes top-to-bottom
 * in list order and asks [policy] every question about what a drag on this lane's markers is allowed
 * to do — the component itself never encodes lane semantics.
 */
interface MarkerLane {
    val markers: Flow<List<TimeMarker>>
    val policy: LanePolicy
}

/**
 * Pluggable per-lane constraint policy. Answers questions on the gesture thread; never renders,
 * never suspends. Chapter tenants implement contiguity/locking here; a hypothetical future
 * Story-World event lane would implement free placement (proven now by the test-only
 * `FreePlacementPolicy` in `DragNegotiatorTest` — the abstraction's acceptance test).
 */
interface LanePolicy {
    /** Whether [marker] may be picked up at all. `false` lanes (e.g. file boundaries) are read-only. */
    fun canDrag(marker: TimeMarker): Boolean

    /**
     * Given a drag's raw proposed [proposedMs] and the lane's current [siblings] (excluding
     * [marker]), returns the legal ms the marker may actually move to. Returning something other
     * than [proposedMs] is a "resist at limit" signal — [DragNegotiator] surfaces this as
     * `DragNegotiator.DragUpdate.resisted` so the renderer can add haptic/visual feedback.
     */
    fun clamp(
        marker: TimeMarker,
        proposedMs: Long,
        siblings: List<TimeMarker>,
    ): Long

    /** Called exactly once, when a drag ends legally, with the final clamped position. */
    fun onCommit(
        marker: TimeMarker,
        newMs: Long,
    )
}
