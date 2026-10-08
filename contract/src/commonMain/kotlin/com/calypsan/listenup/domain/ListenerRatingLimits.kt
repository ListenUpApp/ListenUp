package com.calypsan.listenup.domain

/**
 * The shape of a listener's rating, shared by the server's validation, the client's controls and
 * every platform's UI: 1–5 stars in half steps, stored as half-star units (2..10, so 7 is 3½ stars —
 * an integer never drifts the way 3.4999 does), plus an optional short note.
 */
object ListenerRatingLimits {
    /** One star. */
    const val MIN_HALF_STARS: Int = 2

    /** Five stars. */
    const val MAX_HALF_STARS: Int = 10

    /** The longest note a listener can leave, after trimming. */
    const val NOTE_MAX_CHARS: Int = 280

    /** [note] trimmed, with a blank note meaning no note. */
    fun normalizeNote(note: String?): String? = note?.run { trim().takeIf { it.isNotEmpty() } }

    /**
     * [halfStars] as the number of stars every platform says: "4" for 8, "3.5" for 7. Also works for
     * an average (the Book Detail summary), rounded to the nearest half.
     */
    fun starsLabel(halfStars: Double): String {
        val halves = kotlin.math.round(halfStars).toInt()
        return if (halves % 2 == 0) "${halves / 2}" else "${halves / 2}.5"
    }
}
