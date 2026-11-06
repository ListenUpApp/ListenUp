package com.calypsan.listenup.client.playback

/**
 * Represents the current state of the sleep timer.
 */
sealed interface SleepTimerState {
    /**
     * No timer active.
     */
    data object Inactive : SleepTimerState

    /**
     * Timer is running.
     */
    data class Active(
        val mode: SleepTimerMode,
        val remainingMs: Long,
        val totalMs: Long,
        val startedAt: Long,
    ) : SleepTimerState {
        val progress: Float
            get() = if (totalMs > 0) 1f - (remainingMs.toFloat() / totalMs) else 0f
    }

    /**
     * Timer fired, audio is fading out.
     */
    data object FadingOut : SleepTimerState
}

/**
 * The type of sleep timer.
 */
sealed interface SleepTimerMode {
    /**
     * Timer for a specific duration.
     */
    data class Duration(
        val minutes: Int,
    ) : SleepTimerMode {
        val label: String
            get() =
                when {
                    minutes < 60 -> "$minutes min"
                    minutes == 60 -> "1 hour"
                    minutes % 60 == 0 -> "${minutes / 60} hours"
                    else -> "${minutes / 60}h ${minutes % 60}m"
                }
    }

    /**
     * Timer that fires when the current chapter ends.
     */
    data object EndOfChapter : SleepTimerMode
}
