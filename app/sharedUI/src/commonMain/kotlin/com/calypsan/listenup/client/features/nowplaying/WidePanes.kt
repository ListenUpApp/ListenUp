package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How wide Now Playing divides its space (spec §5.3): the chapter panel shows only beside a cover of at least 240dp.
 * Decided from the space [WideNowPlaying] actually gets ([widePanes]), never from the device (sharedUI rule 11).
 */
internal enum class WidePanes {
    /** Cover · player column · chapter panel (`NP-AndroidTablet`). */
    ThreeColumns,

    /** The cover stacked over the player column, beside the chapter panel: an upright Expanded window. */
    ColumnAndPanel,

    /** Cover beside the controls, Chapters in the settings row and its sheet: no room for the panel. */
    CoverAndControls,
}

/** The chapter panel's width (the board). */
internal const val PANEL_WIDTH_DP = 340

/** The gap between the panes (the board). */
internal const val PANE_GAP_DP = 40

/** The smallest cover the panel may sit beside (§5.3). */
internal const val MIN_COVER_DP = 240

/** The widest the wide cover grows (§5.3, the board). */
internal const val MAX_COVER_DP = 380

/**
 * The narrowest the player column goes: the transport (four 56dp skips, the 96dp play button and their 8dp gaps, 352dp)
 * with the settings row's three tonal pills under it.
 */
internal const val MIN_CONTROLS_WIDTH_DP = 360

/**
 * The height the player column needs below a stacked cover, at font scale 1: the series link, a two-line title, both
 * bylines, the counter and the chapter title, the scrubber with its times, the 96dp transport and the settings row.
 */
internal const val MIN_CONTROLS_HEIGHT_DP = 520

/** The wide header's height (the board's 56dp bar). */
internal const val WIDE_HEADER_HEIGHT_DP = 56

/** The narrowest space that holds three columns: a 240dp cover, the player column's minimum and the panel. */
internal const val THREE_COLUMN_MIN_WIDTH_DP =
    MIN_COVER_DP + PANE_GAP_DP + MIN_CONTROLS_WIDTH_DP + PANE_GAP_DP + PANEL_WIDTH_DP

/** [PANEL_WIDTH_DP] as a [Dp]. */
internal val PanelWidth: Dp = PANEL_WIDTH_DP.dp

/** [PANE_GAP_DP] as a [Dp]. */
internal val PaneGap: Dp = PANE_GAP_DP.dp

/** [MAX_COVER_DP] as a [Dp]. */
internal val MaxWideCover: Dp = MAX_COVER_DP.dp

/** [MIN_CONTROLS_WIDTH_DP] as a [Dp]. */
internal val MinControlsWidth: Dp = MIN_CONTROLS_WIDTH_DP.dp

/** [MIN_CONTROLS_HEIGHT_DP] as a [Dp]. */
internal val MinControlsHeight: Dp = MIN_CONTROLS_HEIGHT_DP.dp

/**
 * Picks [WidePanes] from the space below the header, after insets and padding. A phone on its side ([isShort]) and
 * book posture ([isBookPosture], whose hinge would cut the panes) keep cover and controls. Otherwise three columns
 * where a 240dp cover, the player column and the panel fit across; the player column beside the panel where the width
 * holds the column and the height holds a 240dp cover over it; and cover and controls everywhere else. Pure.
 */
internal fun widePanes(
    maxWidth: Dp,
    maxHeight: Dp,
    isShort: Boolean,
    isBookPosture: Boolean,
): WidePanes {
    if (isShort || isBookPosture) return WidePanes.CoverAndControls
    val besidePanel = maxWidth - PanelWidth - PaneGap
    return when {
        maxWidth >= THREE_COLUMN_MIN_WIDTH_DP.dp -> WidePanes.ThreeColumns
        besidePanel >= MinControlsWidth && maxHeight - MinControlsHeight >= MIN_COVER_DP.dp -> WidePanes.ColumnAndPanel
        else -> WidePanes.CoverAndControls
    }
}
