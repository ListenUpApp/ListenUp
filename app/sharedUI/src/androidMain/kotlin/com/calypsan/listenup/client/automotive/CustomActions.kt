package com.calypsan.listenup.client.automotive

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * Custom session commands for Android Auto and external controllers.
 *
 * These commands extend the standard media controls with audiobook-specific
 * functionality like speed cycling, chapter navigation, and sleep timer.
 */
object CustomActions {
    // Speed control
    const val CYCLE_SPEED = "com.calypsan.listenup.CYCLE_SPEED"

    // Sleep timer
    const val SET_SLEEP_TIMER = "com.calypsan.listenup.SET_SLEEP_TIMER"
    const val CANCEL_SLEEP_TIMER = "com.calypsan.listenup.CANCEL_SLEEP_TIMER"

    // Bundle keys for sleep timer
    const val EXTRA_TIMER_MINUTES = "timer_minutes"

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
     * Create SessionCommand for setting sleep timer.
     *
     * @param minutes Timer duration in minutes
     */
    fun setSleepTimerCommand(minutes: Int): SessionCommand {
        val args =
            Bundle().apply {
                putInt(EXTRA_TIMER_MINUTES, minutes)
            }
        return SessionCommand(SET_SLEEP_TIMER, args)
    }

    /**
     * Create SessionCommand for canceling sleep timer.
     */
    fun cancelSleepTimerCommand(): SessionCommand = SessionCommand(CANCEL_SLEEP_TIMER, Bundle.EMPTY)
}
