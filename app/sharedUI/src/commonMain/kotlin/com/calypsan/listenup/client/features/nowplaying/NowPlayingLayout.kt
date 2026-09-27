package com.calypsan.listenup.client.features.nowplaying

import androidx.window.core.layout.WindowSizeClass

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
