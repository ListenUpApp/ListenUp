package com.calypsan.listenup.client.features.documentviewer

import androidx.compose.ui.unit.IntRect
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

/** An expanded width with at least a medium height: a tablet, or a foldable open flat on its side. */
private fun hasRoomForPane(windowSizeClass: WindowSizeClass): Boolean =
    windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) &&
        windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
