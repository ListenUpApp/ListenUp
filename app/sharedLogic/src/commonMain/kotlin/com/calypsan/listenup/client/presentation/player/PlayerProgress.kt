package com.calypsan.listenup.client.presentation.player

/**
 * The fast-changing position (~4 Hz), split out so a tick never re-emits [PlayerUiState] (§4.2). Only scrubbers,
 * time labels and passive progress lines subscribe. Everything that moves is chapter-scoped; the book is a sentence.
 */
data class PlayerProgress(
    val chapterPositionMs: Long,
    val chapterDurationMs: Long,
    val bookPositionMs: Long,
    val bookDurationMs: Long,
) {
    /** How far through the chapter, 0..1. */
    val chapterFraction: Float get() = fraction(chapterPositionMs, chapterDurationMs)

    /** How far through the book, 0..1. */
    val bookFraction: Float get() = fraction(bookPositionMs, bookDurationMs)

    /** Content time left in the chapter (S10). */
    val chapterRemainingMs: Long get() = (chapterDurationMs - chapterPositionMs).coerceAtLeast(0L)

    /** Content time left in the book (S10). */
    val bookRemainingMs: Long get() = (bookDurationMs - bookPositionMs).coerceAtLeast(0L)

    /**
     * The book's remainder in wall time at [speed]: the Speed sheet's "32h 24m left at this speed", S10's single
     * exception to content time. A non-positive speed gives the content remainder. Pure.
     */
    fun bookRemainingAtSpeedMs(speed: Float): Long =
        if (speed >
            0f
        ) {
            (bookRemainingMs / speed).toLong()
        } else {
            bookRemainingMs
        }

    /** Whole percent through the book, floored (0..100), for the book sentence "Book 11% · …" (§3.2). */
    val bookPercent: Int
        get() =
            if (bookDurationMs <= 0) {
                0
            } else {
                (bookPositionMs.coerceIn(0L, bookDurationMs) * PERCENT / bookDurationMs).toInt()
            }

    /** The values that stand for nothing loaded. */
    companion object {
        /** Nothing loaded. */
        val Zero = PlayerProgress(chapterPositionMs = 0, chapterDurationMs = 0, bookPositionMs = 0, bookDurationMs = 0)
    }
}

private fun fraction(
    position: Long,
    duration: Long,
): Float = if (duration <= 0) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)

private const val PERCENT = 100L
