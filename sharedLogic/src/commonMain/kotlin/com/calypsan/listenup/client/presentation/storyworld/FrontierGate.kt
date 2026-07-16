package com.calypsan.listenup.client.presentation.storyworld

import com.calypsan.listenup.client.domain.model.PlaybackPosition

/**
 * Result of running [FrontierGate.gate] over a list of anchored items.
 *
 * @property visible The items that passed the frontier check, in the same order they
 * were given — [FrontierGate.gate] never re-sorts.
 * @property hiddenCount How many items the filter removed. Surfaced so the UI can show
 * an honest "N hidden" count rather than silently dropping entries.
 */
internal data class Gated<T>(
    val visible: List<T>,
    val hiddenCount: Int,
)

/**
 * Spoiler-safe visibility gate for Story World log entries (spec §6).
 *
 * Every log entry carries an optional anchor — a `(bookId, positionMs)` pair recording
 * where in the listener's own library that entry becomes safe to reveal. Entries whose
 * anchor sits beyond the viewer's listening frontier are hidden by default; the caller
 * decides how to surface the honest hidden count and the session-scoped reveal toggle.
 *
 * The frontier for a book is its [PlaybackPosition.maxPositionMs] — the monotonic
 * high-water mark that never decreases, even across discard/restart. This is
 * deliberately NOT [PlaybackPosition.positionMs] (the resume point), since a user who
 * restarted a book has still *heard* everything up to the old high-water mark.
 */
internal object FrontierGate {
    /**
     * Whether an entry anchored at ([bookId], [positionMs]) is visible given the
     * viewer's current listening [positions].
     *
     * Rules, in order:
     * 1. `bookId == null` → visible (a baseline entry with no spoiler anchor).
     * 2. No stored position for [bookId] → NOT visible (the book was never started).
     * 3. The book is finished ([PlaybackPosition.isFinished]) → visible unconditionally,
     *    including anchors beyond the frontier.
     * 4. `positionMs == null` → visible iff the book has been started, where "started"
     *    means [PlaybackPosition.startedAtMs] is non-null OR
     *    [PlaybackPosition.maxPositionMs] is greater than zero.
     * 5. Otherwise → visible iff `positionMs <= ` [PlaybackPosition.maxPositionMs].
     */
    fun isVisible(
        bookId: String?,
        positionMs: Long?,
        positions: Map<String, PlaybackPosition>,
    ): Boolean {
        if (bookId == null) return true
        val pos = positions[bookId] ?: return false
        if (pos.isFinished) return true
        if (positionMs == null) {
            val started = pos.startedAtMs != null || pos.maxPositionMs > 0
            return started
        }
        return positionMs <= pos.maxPositionMs
    }

    /**
     * Filters [items] down to those visible per [isVisible], preserving input order.
     *
     * [anchorOf] extracts the `(bookId, positionMs)` anchor pair from each item.
     * When [reveal] is true (the viewer explicitly revealed spoilers this session),
     * every item passes through and [Gated.hiddenCount] is zero.
     */
    fun <T> gate(
        items: List<T>,
        positions: Map<String, PlaybackPosition>,
        reveal: Boolean,
        anchorOf: (T) -> Pair<String?, Long?>,
    ): Gated<T> {
        if (reveal) return Gated(visible = items, hiddenCount = 0)

        val visible = mutableListOf<T>()
        var hiddenCount = 0
        for (item in items) {
            val (bookId, positionMs) = anchorOf(item)
            if (isVisible(bookId, positionMs, positions)) {
                visible.add(item)
            } else {
                hiddenCount++
            }
        }
        return Gated(visible = visible, hiddenCount = hiddenCount)
    }
}
