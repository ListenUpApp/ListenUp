package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.ui.graphics.vector.ImageVector

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
            SECONDS_5 -> Icons.Default.Forward5
            SECONDS_10 -> Icons.Default.Forward10
            SECONDS_30 -> Icons.Default.Forward30
            else -> Icons.Default.FastForward
        }

    /** Icon for a backward skip of [seconds]. */
    fun backward(seconds: Int): ImageVector =
        when (seconds) {
            SECONDS_5 -> Icons.Default.Replay5
            SECONDS_10 -> Icons.Default.Replay10
            SECONDS_30 -> Icons.Default.Replay30
            else -> Icons.Default.FastRewind
        }
}
