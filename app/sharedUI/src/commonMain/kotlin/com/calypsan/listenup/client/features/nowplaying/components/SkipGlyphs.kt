package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.ui.graphics.vector.ImageVector
import com.calypsan.listenup.client.design.icons.ListenUpIcons

/** Intervals Material ships a numbered skip glyph for. */
private const val SECONDS_5 = 5
private const val SECONDS_10 = 10
private const val SECONDS_30 = 30

/**
 * Pure mapping from a skip interval (seconds) to the Material icon that depicts it.
 *
 * Material ships numbered replay/forward glyphs for 5, 10 and 30 seconds only, and the skip
 * presets run to 120 s forward and 60 s back. A numbered glyph is therefore right only when it is
 * *exactly* right — "nearest available number" is still a wrong number on a button — so anything
 * unmatched falls back to the value-neutral fast-forward/rewind pair.
 *
 * Same rule as `SkipCommandIcons` (the Media3 platform surfaces) and iOS's `PlayerGlyphs.swift`:
 * one policy, every surface.
 */
object SkipGlyphs {
    /** Icon for a forward skip of [seconds]. */
    fun forward(seconds: Int): ImageVector =
        when (seconds) {
            SECONDS_5 -> ListenUpIcons.Forward5Filled
            SECONDS_10 -> ListenUpIcons.Forward10Filled
            SECONDS_30 -> ListenUpIcons.Forward30Filled
            else -> ListenUpIcons.FastForwardFilled
        }

    /** Icon for a backward skip of [seconds]. */
    fun backward(seconds: Int): ImageVector =
        when (seconds) {
            SECONDS_5 -> ListenUpIcons.Replay5Filled
            SECONDS_10 -> ListenUpIcons.Replay10Filled
            SECONDS_30 -> ListenUpIcons.Replay30Filled
            else -> ListenUpIcons.FastRewindFilled
        }
}
