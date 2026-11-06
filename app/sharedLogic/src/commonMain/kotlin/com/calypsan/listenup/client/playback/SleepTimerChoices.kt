package com.calypsan.listenup.client.playback

/**
 * What the sleep sheet offers, decided once so Android, iOS and web list the same choices in the
 * same order and apply the same two rules: End of chapter only for a chaptered book, and extend only for a duration.
 * An object, so Swift sees `SleepTimerChoices.shared`.
 */
object SleepTimerChoices {
    /** The durations, in minutes, in the order every sheet lists them after End of chapter. */
    val DURATION_MINUTES: List<Int> = listOf(15, 30, 45, 60, 120)

    /** What a running duration timer can be extended by, in minutes. */
    val EXTEND_MINUTES: List<Int> = listOf(5, 10, 15)

    /** The fewest minutes a custom timer takes. */
    const val CUSTOM_MIN_MINUTES: Int = 1

    /** The most minutes a custom timer takes: twelve hours. */
    const val CUSTOM_MAX_MINUTES: Int = 720

    /** How far the custom stepper's − and + move, in minutes (the boards: "5 minutes less / more"). */
    const val CUSTOM_STEP_MINUTES: Int = 5

    /** Where the custom stepper starts (the boards). */
    const val CUSTOM_DEFAULT_MINUTES: Int = 20

    /** End of chapter is offered only when the book has chapters: a whole-book "chapter" ends with the book. */
    fun offersEndOfChapter(isWholeBook: Boolean): Boolean = !isWholeBook

    /**
     * Only a running duration timer can be extended. An end-of-chapter timer ends with the chapter, and the shared
     * timer ignores an extend there, so its +5 / +10 / +15 would do nothing.
     */
    fun canExtend(timer: SleepTimerState): Boolean = (timer as? SleepTimerState.Active)?.mode is SleepTimerMode.Duration

    /**
     * One press of the custom stepper: a value off the five-minute grid first snaps onto it in the direction
     * pressed, and the result stays within [CUSTOM_STEP_MINUTES]..[CUSTOM_MAX_MINUTES]. Pure.
     */
    fun stepCustom(
        minutes: Int,
        up: Boolean,
    ): Int {
        val below = minutes - minutes.mod(CUSTOM_STEP_MINUTES)
        val next =
            when {
                below != minutes -> if (up) below + CUSTOM_STEP_MINUTES else below
                up -> minutes + CUSTOM_STEP_MINUTES
                else -> minutes - CUSTOM_STEP_MINUTES
            }
        return next.coerceIn(CUSTOM_STEP_MINUTES, CUSTOM_MAX_MINUTES)
    }

    /**
     * A typed custom value (web's number field): whole minutes clamped into
     * [CUSTOM_MIN_MINUTES]..[CUSTOM_MAX_MINUTES], or null when [raw] is not a number. Pure.
     */
    fun parseCustom(raw: String): Int? = raw.trim().toIntOrNull()?.coerceIn(CUSTOM_MIN_MINUTES, CUSTOM_MAX_MINUTES)

    /**
     * Where the custom stepper opens: the user's default sleep timer clamped into
     * [CUSTOM_MIN_MINUTES]..[CUSTOM_MAX_MINUTES], or [CUSTOM_DEFAULT_MINUTES] when there is none (Off). Pure.
     */
    fun startingCustomMinutes(defaultMinutes: Int?): Int =
        defaultMinutes?.coerceIn(CUSTOM_MIN_MINUTES, CUSTOM_MAX_MINUTES) ?: CUSTOM_DEFAULT_MINUTES

    /**
     * What every Settings screen offers as the default sleep timer: Off (`null`), then [DURATION_MINUTES], with
     * [current] added in order when it is something else (an old 5, 10, 20 or 90), so a stored value is a checked row,
     * never a blank one.
     */
    fun defaultChoicesIncluding(current: Int?): List<Int?> {
        val durations =
            if (current == null ||
                current in DURATION_MINUTES
            ) {
                DURATION_MINUTES
            } else {
                (DURATION_MINUTES + current).sorted()
            }
        return listOf(null) + durations
    }
}
