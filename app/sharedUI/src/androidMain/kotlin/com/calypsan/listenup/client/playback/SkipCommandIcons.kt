package com.calypsan.listenup.client.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton

/**
 * Pure mapping from a skip interval (seconds) to the Media3 [CommandButton] icon that depicts it.
 *
 * Media3 ships numbered skip glyphs for a fixed set of intervals only; our presets run to 120 s
 * forward and 60 s back, so a numbered glyph is right only when it is *exactly* right. "Nearest
 * available number" is still a wrong number drawn on a button the user presses in a car, so
 * anything unmatched falls back to Media3's own value-neutral [CommandButton.ICON_SKIP_FORWARD] /
 * [CommandButton.ICON_SKIP_BACK].
 *
 * This is the same rule iOS already applies in `PlayerGlyphs.swift` — one policy, both platforms.
 */
@OptIn(UnstableApi::class)
object SkipCommandIcons {
    /** Media3 icon for a forward skip of [seconds]. */
    fun forward(seconds: Int): Int =
        when (seconds) {
            SECONDS_5 -> CommandButton.ICON_SKIP_FORWARD_5
            SECONDS_10 -> CommandButton.ICON_SKIP_FORWARD_10
            SECONDS_15 -> CommandButton.ICON_SKIP_FORWARD_15
            SECONDS_30 -> CommandButton.ICON_SKIP_FORWARD_30
            else -> CommandButton.ICON_SKIP_FORWARD
        }

    /** Media3 icon for a backward skip of [seconds]. */
    fun backward(seconds: Int): Int =
        when (seconds) {
            SECONDS_5 -> CommandButton.ICON_SKIP_BACK_5
            SECONDS_10 -> CommandButton.ICON_SKIP_BACK_10
            SECONDS_15 -> CommandButton.ICON_SKIP_BACK_15
            SECONDS_30 -> CommandButton.ICON_SKIP_BACK_30
            else -> CommandButton.ICON_SKIP_BACK
        }

    private const val SECONDS_5 = 5
    private const val SECONDS_10 = 10
    private const val SECONDS_15 = 15
    private const val SECONDS_30 = 30
}
