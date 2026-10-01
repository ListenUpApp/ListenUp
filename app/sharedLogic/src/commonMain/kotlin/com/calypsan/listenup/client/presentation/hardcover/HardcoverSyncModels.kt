package com.calypsan.listenup.client.presentation.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverSyncProblem

/** What the Hardcover screen's sync line says. */
sealed interface HardcoverSyncStatus {
    /** Nothing in flight and nothing wrong: show when it last synced, and "Sync now". */
    data object Idle : HardcoverSyncStatus

    /** A sync the user asked for is running. */
    data object Syncing : HardcoverSyncStatus

    /** Sync isn't keeping up, for [problem]: say so in plain words, with "Try again". */
    data class Problem(
        val problem: HardcoverSyncProblem,
    ) : HardcoverSyncStatus
}

/**
 * A book that needs a Hardcover match, as the Needs a match list shows it: from the library on this
 * device, so it has its cover and works offline once listed.
 */
data class HardcoverBookToMatch(
    val bookId: String,
    val title: String,
    val authorNames: String,
    val coverPath: String?,
    val coverHash: String?,
)

/**
 * The Hardcover book a ListenUp book is matched to. [title] is null when Hardcover couldn't be asked
 * for it; [chosenByYou] is true for a match the user picked rather than one ListenUp found.
 */
data class HardcoverMatchedBook(
    val hcBookId: Long,
    val title: String?,
    val authors: List<String>,
    val releaseYear: Int?,
    val chosenByYou: Boolean,
)
