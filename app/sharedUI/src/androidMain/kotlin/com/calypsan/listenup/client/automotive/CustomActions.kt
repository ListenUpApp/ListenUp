package com.calypsan.listenup.client.automotive

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * Custom session commands for Android Auto and external controllers.
 *
 * These commands extend the standard media controls with audiobook-specific
 * functionality like speed cycling and chapter navigation.
 */
object CustomActions {
    // Speed control
    const val CYCLE_SPEED = "com.calypsan.listenup.CYCLE_SPEED"

    // In-app book-relative seek. The session player is ChapterWindowPlayer, the chapter-scoped
    // presentation wrapper (see its class KDoc), so a plain controller.seekTo(index, positionMs)
    // is reinterpreted chapter-relatively and clamped to the current chapter window — it cannot
    // express a book position. This custom command carries the book-relative target explicitly
    // instead, and PlaybackService's onCustomCommand handler resolves it against the raw
    // transport player, bypassing the wrapper's chapter-relative reinterpretation entirely.
    const val SEEK_TO_BOOK_POSITION = "com.calypsan.listenup.SEEK_TO_BOOK_POSITION"

    // Bundle key for the book-relative seek target, in milliseconds.
    const val EXTRA_BOOK_POSITION_MS = "book_position_ms"

    /**
     * Speed options for cycling (in order).
     */
    val SPEED_OPTIONS = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    /**
     * Get the next speed in the cycle.
     *
     * @param currentSpeed The current playback speed
     * @return The next speed in the cycle, wrapping back to 1.0x after 2.0x
     */
    fun getNextSpeed(currentSpeed: Float): Float {
        // Find closest speed option
        val currentIndex =
            SPEED_OPTIONS.indexOfFirst {
                kotlin.math.abs(it - currentSpeed) < 0.01f
            }

        return if (currentIndex == -1 || currentIndex == SPEED_OPTIONS.lastIndex) {
            SPEED_OPTIONS.first() // Wrap to beginning
        } else {
            SPEED_OPTIONS[currentIndex + 1]
        }
    }

    /**
     * Format speed for display.
     *
     * @param speed The playback speed
     * @return Formatted string like "1.5x"
     */
    fun formatSpeed(speed: Float): String =
        if (speed == speed.toInt().toFloat()) {
            "${speed.toInt()}x"
        } else {
            "${speed}x"
        }

    /**
     * Create SessionCommand for speed cycling.
     */
    fun cycleSpeedCommand(): SessionCommand = SessionCommand(CYCLE_SPEED, Bundle.EMPTY)

    /**
     * Create SessionCommand for the in-app book-relative seek transport.
     *
     * Advertised with [Bundle.EMPTY] extras — [SessionCommand] equality (used by Media3 to match
     * an advertised command against an incoming one) ignores extras, so this matches sends built
     * with [seekToBookPositionArgs] carrying the actual target.
     */
    fun seekToBookPositionCommand(): SessionCommand = SessionCommand(SEEK_TO_BOOK_POSITION, Bundle.EMPTY)

    /**
     * Build the args [Bundle] for a [seekToBookPositionCommand] send: the book-relative seek
     * target, in milliseconds.
     */
    fun seekToBookPositionArgs(positionMs: Long): Bundle =
        Bundle().apply {
            putLong(EXTRA_BOOK_POSITION_MS, positionMs)
        }
}
