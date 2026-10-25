package com.calypsan.listenup.client.features.documentviewer

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.foldable.Fold
import com.calypsan.listenup.client.foldable.Posture

/** The two shapes the companion PDF reader takes. */
internal sealed interface ReaderLayout {
    /** The page alone, the now-playing strip docked under it. */
    data object Single : ReaderLayout

    /** The page and the listening pane side by side, divided at [splitAt]. */
    data class Beside(
        val splitAt: ReaderSplit,
    ) : ReaderLayout
}

/** Where [ReaderLayout.Beside] divides the page from the listening pane. */
internal sealed interface ReaderSplit {
    /** At the hinge of a device half open like a book; [bounds] are in window pixels. */
    data class Hinge(
        val bounds: IntRect,
    ) : ReaderSplit

    /** A fixed-width pane against the trailing edge of a wide window. */
    data object TrailingPane : ReaderSplit
}

/**
 * Which layout the reader takes for this window and [fold].
 *
 * One spec with iOS's `ReaderLayoutMode` (`Features/DocumentReader/ReaderLayout.swift`); change both together. Nothing
 * playing keeps the page alone. A device half open like a book puts the page and the player on facing pages, split at
 * the hinge, so it needs the hinge's bounds; without them the window decides. Half open on a table keeps the page alone
 * (a page reads badly on a half lying flat) with its now-playing strip. Otherwise an expanded width with at least a
 * medium height has room for the pane; a phone, upright or on its side, keeps the page alone.
 */
internal fun readerLayout(
    windowSizeClass: WindowSizeClass,
    fold: Fold,
    hasCurrentBook: Boolean,
): ReaderLayout {
    val hinge = fold.hingeBounds
    return when {
        !hasCurrentBook -> ReaderLayout.Single
        fold.posture == Posture.BOOK && hinge != null -> ReaderLayout.Beside(ReaderSplit.Hinge(hinge))
        fold.posture == Posture.TABLETOP -> ReaderLayout.Single
        hasRoomForPane(windowSizeClass) -> ReaderLayout.Beside(ReaderSplit.TrailingPane)
        else -> ReaderLayout.Single
    }
}

/** The widths of the page, the gap over the hinge, and the listening pane, start to end, in pixels. */
internal data class ReaderColumns(
    val page: Int,
    val gap: Int,
    val pane: Int,
)

/**
 * The columns for [split] across a frame [width] pixels wide whose left edge is at [left] in the window — the hinge's
 * bounds are window pixels. A hinge keeps [clearance] either side, so nothing sits on the crease even when the fold
 * reports no thickness; its bounds run left to right, so right to left the page takes the right-hand side of it.
 * The pane in a wide window is [paneWidth] wide. One spec with iOS's `ReaderColumns`.
 */
internal fun readerColumns(
    split: ReaderSplit,
    left: Int,
    width: Int,
    paneWidth: Int,
    clearance: Int,
    layoutDirection: LayoutDirection,
): ReaderColumns =
    when (split) {
        is ReaderSplit.Hinge -> {
            val leftOfHinge = (split.bounds.left - left - clearance).coerceAtLeast(0)
            val rightOfHinge = (left + width - split.bounds.right - clearance).coerceAtLeast(0)
            val (page, pane) =
                if (layoutDirection == LayoutDirection.Rtl) rightOfHinge to leftOfHinge else leftOfHinge to rightOfHinge
            ReaderColumns(page = page, gap = (width - page - pane).coerceAtLeast(0), pane = pane)
        }

        ReaderSplit.TrailingPane -> {
            val pane = paneWidth.coerceAtMost(width)
            ReaderColumns(page = width - pane, gap = 0, pane = pane)
        }
    }

/** An expanded width with at least a medium height: a tablet, or a foldable open flat on its side. */
private fun hasRoomForPane(windowSizeClass: WindowSizeClass): Boolean =
    windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) &&
        windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
