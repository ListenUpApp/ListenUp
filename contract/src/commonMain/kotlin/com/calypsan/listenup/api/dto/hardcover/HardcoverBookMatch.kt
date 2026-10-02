package com.calypsan.listenup.api.dto.hardcover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where one linked book stands with Hardcover, for the Book Detail row. */
@Serializable
enum class HardcoverBookSync {
    /** ListenUp's record on Hardcover is current: nothing is waiting to go. */
    UP_TO_DATE,

    /** Something is queued for Hardcover and will go out (or is retrying). */
    WAITING,

    /** ListenUp hasn't sent anything for this book yet. */
    NOTHING_SENT_YET,

    /** The user removed ListenUp's record on Hardcover, so this listen-through no longer syncs; a new one will. */
    REMOVED_ON_HARDCOVER,
}

/** How one of the caller's books is matched on Hardcover, as [com.calypsan.listenup.api.HardcoverService.bookMatch] answers. */
@Serializable
sealed interface HardcoverBookMatch {
    /** ListenUp hasn't tried to match it yet: nothing about it has needed Hardcover. */
    @Serializable
    @SerialName("HardcoverBookMatch.Unmatched")
    data object Unmatched : HardcoverBookMatch

    /** ListenUp couldn't tell which Hardcover book it is and won't guess; its pushes wait until the user picks. */
    @Serializable
    @SerialName("HardcoverBookMatch.NeedsMatch")
    data object NeedsMatch : HardcoverBookMatch

    /**
     * The listener keeps this book off Hardcover (#1541): nothing about it is sent, and nothing about it is
     * brought in. Answered whatever the book's link, which is kept for when it syncs again.
     */
    @Serializable
    @SerialName("HardcoverBookMatch.KeptOff")
    data object KeptOff : HardcoverBookMatch

    /**
     * Linked to Hardcover book [hcBookId], as edition [hcEditionId] when one is named. [title], [authors]
     * and [releaseYear] describe it as Hardcover's catalog does; [title] is null when Hardcover couldn't
     * be asked. [chosenByYou] is true for a match the user picked. [sync] is where it stands. [method] is
     * how the match was made — what [com.calypsan.listenup.api.HardcoverService.restoreMatch] needs to put
     * it back exactly; null from a server that doesn't say. [readsInReaders] is true when the listener's
     * Hardcover reads of it show in Readers, and [onToReadFromHardcover] when Hardcover's Want to Read put it
     * on their To Read shelf: what keeping it off would take out of ListenUp (#1541). Both default to false,
     * so an older server's payload never asks for a confirmation it can't justify.
     */
    @Serializable
    @SerialName("HardcoverBookMatch.Linked")
    data class Linked(
        @SerialName("hcBookId") val hcBookId: Long,
        @SerialName("hcEditionId") val hcEditionId: Long? = null,
        @SerialName("title") val title: String? = null,
        @SerialName("authors") val authors: List<String> = emptyList(),
        @SerialName("releaseYear") val releaseYear: Int? = null,
        @SerialName("chosenByYou") val chosenByYou: Boolean = false,
        @SerialName("sync") val sync: HardcoverBookSync = HardcoverBookSync.NOTHING_SENT_YET,
        @SerialName("method") val method: HardcoverMatchMethod? = null,
        @SerialName("readsInReaders") val readsInReaders: Boolean = false,
        @SerialName("onToReadFromHardcover") val onToReadFromHardcover: Boolean = false,
    ) : HardcoverBookMatch
}
