package com.calypsan.listenup.domain

/**
 * Where a listener is with one book: the one rule every surface reads its percent, time left and "finished / in
 * progress / not started" from, on the server and every client. Before it, Library and Home rounded the percent,
 * Series and the readers list truncated it, Book Detail ignored the end credits and iOS rebuilt the time left from
 * a rounded fraction, so one book read 39% on one screen and 40% on the next.
 *
 * Build it only with [of]. A platform shows [InProgress.percent] and formats [InProgress.remainingMs]; it never
 * divides a position by a duration itself.
 */
sealed interface BookProgress {
    /** Never played, or played back to the very start; or a book of unknown length that is not flagged finished. */
    data object NotStarted : BookProgress

    /**
     * Started and not finished: [positionMs] is past the start and short of the end credits of a book of [durationMs]
     * (always > 0).
     */
    @ConsistentCopyVisibility
    data class InProgress internal constructor(
        val positionMs: Long,
        val durationMs: Long,
    ) : BookProgress {
        /** How far through the book, `0..1`, for drawing a bar. Never for a printed percent: that is [percent]. */
        val fraction: Float get() = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

        /**
         * The whole percent to print: the fraction floored, then held to `1..99`, so a started book never reads 0% and
         * an unfinished one never reads 100%. The only rounding of a book's progress anywhere.
         */
        val percent: Int get() = (positionMs * PERCENT / durationMs).toInt().coerceIn(MIN_PERCENT, MAX_PERCENT)

        /** What is left to listen to, in milliseconds; never negative. */
        val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0L)
    }

    /** Finished, per [FinishedPolicy]: the flag, or a position inside the end credits. */
    data object Finished : BookProgress

    /** The one factory. See [of]. */
    companion object {
        private const val PERCENT = 100L
        private const val MIN_PERCENT = 1
        private const val MAX_PERCENT = 99

        /**
         * The progress of a book of [durationMs] whose saved (or live) position is [positionMs] (null when there is no
         * position at all), with the stored [finishedFlag].
         *
         * - [Finished] whenever [FinishedPolicy.isFinished] says so: the flag, or a position within the end credits
         *   (which includes a position at or past the end).
         * - [NotStarted] for no position, a position at or before zero, or an **unknown duration** (`<= 0`): without a
         *   length there is no percent and no time left to show, and an in-progress book always has both, so a book
         *   that has not been measured yet reads as not started until it is (or until it is flagged finished).
         * - [InProgress] otherwise.
         */
        fun of(
            positionMs: Long?,
            durationMs: Long,
            finishedFlag: Boolean,
        ): BookProgress {
            val position = positionMs ?: 0L
            return when {
                FinishedPolicy.isFinished(position, durationMs, finishedFlag) -> Finished
                position <= 0L || durationMs <= 0L -> NotStarted
                else -> InProgress(positionMs = position, durationMs = durationMs)
            }
        }
    }
}
