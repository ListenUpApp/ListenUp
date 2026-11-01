package com.calypsan.listenup.web.features.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.home.TimeOfDay
import kotlinx.browser.window
import org.w3c.dom.events.Event

/** Everything Home can ask for; every gesture leaves as one of these (the page stays pure). */
data class HomeActions(
    /** Opens a book. */
    val onOpenBook: (String) -> Unit,
    /** P-R5: the player's play entry, from the saved position (C18). */
    val onPlay: (String) -> Unit,
    /** Opens the Library (Browse the Library). */
    val onOpenLibrary: () -> Unit,
    /** Opens Discover: the quiet slots' Discover button. */
    val onOpenDiscover: () -> Unit,
    /** Opens a shelf. */
    val onOpenShelf: (String) -> Unit,
    /** Starts a new shelf. */
    val onCreateShelf: () -> Unit,
    /** Re-runs a failed Home. Offered only on the failed page — web has no page-level refresh. */
    val onRetry: () -> Unit,
    /** Re-runs failed stats, without touching the rest of the page. */
    val onRetryStats: () -> Unit,
    /** Opens a finished book with its readers in view (the finishers line). */
    val onOpenReaders: (String) -> Unit = {},
    /** Dismisses a book's just-finished card on every device. */
    val onDismissFinished: (String) -> Unit = {},
    /** Rates a finished book on the 2..10 half-star scale. */
    val onRateFinished: (String, Int) -> Unit = { _, _ -> },
    /** Asks for the server again (the "Can't reach your server" notice's Retry). */
    val onRetryConnection: () -> Unit = {},
    /** Opens the Library filtered to In progress: Everything in progress's "In Library" (spec §6.4). */
    val onOpenInProgress: () -> Unit = {},
    /** Opens Discover's leaderboard on Week: the stats footer (spec §6.4). */
    val onOpenLeaderboard: () -> Unit = {},
    /** Opens the My shelves list: My shelves' See all (H24). */
    val onSeeAllShelves: () -> Unit = {},
) {
    /** Holds [None]. */
    companion object {
        /** For specs that press nothing. */
        val None = HomeActions({}, {}, {}, {}, {}, {}, {}, {})
    }
}

/**
 * "Good evening, Simon" (spec §3.1, web): worded here, never by the ViewModel (H2). A new listener is greeted
 * "Welcome, Simon" ([isWelcome], spec §2.3 NewListener).
 */
internal fun homeGreeting(
    timeOfDay: TimeOfDay,
    firstName: String?,
    isWelcome: Boolean = false,
): String {
    val greeting = if (isWelcome) HomeWords.WELCOME else HomeWords.greeting(timeOfDay)
    return if (firstName.isNullOrBlank()) greeting else "$greeting, $firstName"
}

/** The wide arrangement starts where the shell's sidebar does (spec §3.4 "desktop ≥ 1024 px"). */
internal const val HOME_WIDE_QUERY = "(min-width: 1024px)"

/** Whether the page is wide, now and whenever the media query flips (C9). */
@Composable
internal fun rememberHomeIsWide(): Boolean {
    var wide by remember { mutableStateOf(window.matchMedia(HOME_WIDE_QUERY).matches) }
    DisposableEffect(Unit) {
        val query = window.matchMedia(HOME_WIDE_QUERY)
        wide = query.matches
        val listener: (Event) -> Unit = { wide = query.matches }
        query.addEventListener("change", listener)
        onDispose { query.removeEventListener("change", listener) }
    }
    return wide
}
