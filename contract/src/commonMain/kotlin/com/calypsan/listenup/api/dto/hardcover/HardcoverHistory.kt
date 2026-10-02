package com.calypsan.listenup.api.dto.hardcover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The listener's ListenUp history on Hardcover (#1540): the books they finished in ListenUp before
 * connecting, which ListenUp offers to send as Read with when they started and finished. Carried on
 * [HardcoverConnection.Connected.history] and republished as it changes. Counts are distinct books.
 */
@Serializable
sealed interface HardcoverHistory {
    /** Nothing to offer: no earlier books, or the offer was sent, seen and dismissed. */
    @Serializable
    @SerialName("HardcoverHistory.None")
    data object None : HardcoverHistory

    /** The one-time card: [bookCount] books were finished in ListenUp before connecting. */
    @Serializable
    @SerialName("HardcoverHistory.Offer")
    data class Offer(
        @SerialName("bookCount") val bookCount: Int,
    ) : HardcoverHistory

    /** After "Not now": the quiet "Send earlier books" row, for [bookCount] books not yet sent. */
    @Serializable
    @SerialName("HardcoverHistory.Available")
    data class Available(
        @SerialName("bookCount") val bookCount: Int,
    ) : HardcoverHistory

    /** On its way: [sentBooks] of [totalBooks] are done; the count moves only as each book completes. */
    @Serializable
    @SerialName("HardcoverHistory.Sending")
    data class Sending(
        @SerialName("sentBooks") val sentBooks: Int,
        @SerialName("totalBooks") val totalBooks: Int,
    ) : HardcoverHistory

    /**
     * Sent. [sentBooks] are now on Hardcover — including those it already had, which were skipped — and
     * [needsMatchBooks] wait for the listener to match them, then go on their own.
     */
    @Serializable
    @SerialName("HardcoverHistory.Done")
    data class Done(
        @SerialName("sentBooks") val sentBooks: Int,
        @SerialName("needsMatchBooks") val needsMatchBooks: Int,
    ) : HardcoverHistory
}
