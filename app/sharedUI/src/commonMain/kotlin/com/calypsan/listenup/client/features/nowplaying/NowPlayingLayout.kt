package com.calypsan.listenup.client.features.nowplaying

import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.foldable.Fold
import com.calypsan.listenup.client.foldable.Posture

/**
 * Whether Now Playing lays the cover and the controls side by side ([WideNowPlaying]) rather than
 * stacking them ([CompactNowPlaying]).
 *
 * Always on expanded widths. On a medium-width window it depends on the height: a phone on its side
 * (a car dock, a bedside stand) is too short to stack, so a stacked player would push the transport
 * below the fold.
 */
internal fun useWideNowPlaying(windowSizeClass: WindowSizeClass): Boolean =
    windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) ||
        (
            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) &&
                !windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
        )

/** The three shapes Now Playing takes. */
internal enum class NowPlayingLayout {
    /** Cover over controls in one column ([CompactNowPlaying]). */
    Stacked,

    /** Cover beside controls ([WideNowPlaying]). */
    SideBySide,

    /** Cover and title above a horizontal hinge, the transport below it ([TabletopNowPlaying]). */
    Tabletop,
}

/**
 * Which layout Now Playing takes for this window and [fold].
 *
 * A device half open on a table splits the player at the hinge, whatever the window size: that is
 * the posture a player is made for, the screen standing up to be read and the controls lying flat to
 * be touched. It needs the hinge's bounds to split at, so without them the window decides. Half open
 * like a book puts the cover and the controls on facing pages: the side-by-side layout's panes share
 * the width equally, so its gap falls on a centred hinge.
 */
internal fun nowPlayingLayout(
    windowSizeClass: WindowSizeClass,
    fold: Fold,
): NowPlayingLayout =
    when {
        fold.posture == Posture.TABLETOP && fold.hingeBounds != null -> NowPlayingLayout.Tabletop
        fold.posture == Posture.BOOK -> NowPlayingLayout.SideBySide
        useWideNowPlaying(windowSizeClass) -> NowPlayingLayout.SideBySide
        else -> NowPlayingLayout.Stacked
    }
