package com.calypsan.listenup.client.features.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf

/** A place inside a tab, other than its top, that a link elsewhere can open (spec §6.4). */
sealed interface ShellLanding {
    /** The tab this landing opens. */
    val destination: ShellDestination

    /** Discover, its leaderboard in view with Week selected: the stats card's footer link. */
    data object DiscoverLeaderboard : ShellLanding {
        override val destination: ShellDestination = ShellDestination.Discover
    }

    /** Library's Books, filtered to In progress for this session: Home's "In Library" link. */
    data object LibraryInProgress : ShellLanding {
        override val destination: ShellDestination = ShellDestination.Library
    }

    /** Library's Books, sorted Added newest first for this session: Discover's "In Library" link. */
    data object LibraryNewestFirst : ShellLanding {
        override val destination: ShellDestination = ShellDestination.Library
    }
}

/**
 * The shell's one pending landing. A screen [request]s it; the destination tab [consume]s it once.
 * Held by the shell, so a screen opens "Discover at its leaderboard" without knowing how tabs switch.
 */
@Stable
class ShellLandings(
    private val selectDestination: (ShellDestination) -> Unit,
) {
    /** The landing waiting for its tab, or null. */
    var pending: ShellLanding? by mutableStateOf(null)
        private set

    /** Open [landing]'s tab and leave the landing for it to consume. */
    fun request(landing: ShellLanding) {
        pending = landing
        selectDestination(landing.destination)
    }

    /** True exactly once, for the screen [landing] is meant for. */
    fun consume(landing: ShellLanding): Boolean = (pending == landing).also { if (it) pending = null }
}

/** The shell's landings; null outside the shell (previews, tests, the frozen desktop's other roots). */
val LocalShellLandings = staticCompositionLocalOf<ShellLandings?> { null }

/**
 * Runs [onLand] once each time [landing] is requested while this composable is on screen.
 *
 * One long-lived collector rather than an effect keyed on [ShellLandings.pending]: consuming clears
 * the pending landing, and a keyed effect would restart on that change and cancel [onLand] mid-scroll.
 */
@Composable
fun LandingEffect(
    landing: ShellLanding,
    onLand: suspend () -> Unit,
) {
    val landings = LocalShellLandings.current ?: return
    val currentOnLand by rememberUpdatedState(onLand)
    LaunchedEffect(landings, landing) {
        snapshotFlow { landings.pending }.collect {
            if (landings.consume(landing)) currentOnLand()
        }
    }
}
