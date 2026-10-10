package com.calypsan.listenup.client.presentation.library

/**
 * What a Library card says about where the reader is with a book (spec §2.6).
 *
 * Platforms format the numbers themselves (units differ: iOS says "hr"). The subtypes are nested
 * exactly one level, so each gets a flat Swift Export alias (`BookCardStatusInProgress`).
 */
sealed interface BookCardStatus {
    /** Never played: the card's last line is the book's length. */
    data class NotStarted(
        val durationMs: Long,
    ) : BookCardStatus

    /** Started and not finished: a progress mark plus "40h 31m left" in the Library action colour. */
    data class InProgress(
        val fraction: Float,
        val timeLeftMs: Long,
    ) : BookCardStatus

    /** Finished: a finished badge plus "Finished · 12h 4m". */
    data class Finished(
        val durationMs: Long,
    ) : BookCardStatus
}
