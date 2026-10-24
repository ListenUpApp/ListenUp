package com.calypsan.listenup.client.presentation.player

import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState

/**
 * What the sleep sheet offers (spec §5.5.2, PT1–PT8), decided once so Android, iOS and web list the same choices in the
 * same order and apply the same two rules: End of chapter only for a chaptered book, and extend only for a duration.
 * An object, so Swift sees `SleepTimerChoices.shared`.
 */
object SleepTimerChoices {
    /** The durations, in minutes, in the order every sheet lists them after End of chapter (§5.5.2). */
    val DURATION_MINUTES: List<Int> = listOf(15, 30, 45, 60, 120)

    /** What a running duration timer can be extended by, in minutes (PT5). */
    val EXTEND_MINUTES: List<Int> = listOf(5, 10, 15)

    /** The fewest minutes a custom timer takes (PT8). */
    const val CUSTOM_MIN_MINUTES: Int = 1

    /** The most minutes a custom timer takes (PT8): twelve hours. */
    const val CUSTOM_MAX_MINUTES: Int = 720

    /** How far the custom stepper's − and + move, in minutes (the boards: "5 minutes less / more"). */
    const val CUSTOM_STEP_MINUTES: Int = 5

    /** Where the custom stepper starts (the boards). */
    const val CUSTOM_DEFAULT_MINUTES: Int = 20

    /** End of chapter is offered only when the book has chapters: a whole-book "chapter" ends with the book (PT1). */
    fun offersEndOfChapter(chapter: PlayerChapter): Boolean = !chapter.isWholeBook

    /**
     * Only a running duration timer can be extended. An end-of-chapter timer ends with the chapter, and the shared
     * timer ignores an extend there, so its +5 / +10 / +15 would do nothing (open question 1).
     */
    fun canExtend(timer: SleepTimerState): Boolean = (timer as? SleepTimerState.Active)?.mode is SleepTimerMode.Duration

    /**
     * One press of the custom stepper (PT8): a value off the five-minute grid first snaps onto it in the direction
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
     * A typed custom value (web's number field, PT8): whole minutes clamped into
     * [CUSTOM_MIN_MINUTES]..[CUSTOM_MAX_MINUTES], or null when [raw] is not a number. Pure.
     */
    fun parseCustom(raw: String): Int? = raw.trim().toIntOrNull()?.coerceIn(CUSTOM_MIN_MINUTES, CUSTOM_MAX_MINUTES)
}
