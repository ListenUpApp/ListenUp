package com.calypsan.listenup.web.features.home

import com.calypsan.listenup.client.presentation.home.TimeOfDay

/** Everything Home can ask for; every gesture leaves as one of these (the page stays pure). */
data class HomeActions(
    /** Opens a book. */
    val onOpenBook: (String) -> Unit,
    /** P-R5: the player's play entry, from the saved position (C18). */
    val onPlay: (String) -> Unit,
    /** Opens the Library. */
    val onOpenLibrary: () -> Unit,
    /** The stats footer's leaderboard link; PR 5 adds the Week focus (spec §6.4). */
    val onOpenDiscover: () -> Unit,
    /** Opens a shelf. */
    val onOpenShelf: (String) -> Unit,
    /** Starts a new shelf. */
    val onCreateShelf: () -> Unit,
    /** Re-runs a failed Home. Offered only on the failed page — web has no page-level refresh. */
    val onRetry: () -> Unit,
    /** Re-runs failed stats, without touching the rest of the page. */
    val onRetryStats: () -> Unit,
) {
    /** Holds [None]. */
    companion object {
        /** For specs that press nothing. */
        val None = HomeActions({}, {}, {}, {}, {}, {}, {}, {})
    }
}

/** "Good evening, Simon" (spec §3.1, web): worded here, never by the ViewModel (H2). */
internal fun homeGreeting(
    timeOfDay: TimeOfDay,
    firstName: String?,
): String {
    val greeting =
        when (timeOfDay) {
            TimeOfDay.Morning -> "Good morning"
            TimeOfDay.Afternoon -> "Good afternoon"
            TimeOfDay.Evening -> "Good evening"
            TimeOfDay.Night -> "Good night"
        }
    return if (firstName.isNullOrBlank()) greeting else "$greeting, $firstName"
}
