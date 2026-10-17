package com.calypsan.listenup.web.features.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.client.presentation.home.TimeOfDay
import kotlinx.browser.window
import org.w3c.dom.events.Event

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

/** One section of Home, in the order [homeBlocks] puts them. */
internal enum class HomeBlock {
    Resume,
    ResumeBesideStats,
    NothingToResume,
    AlsoInProgress,
    Stats,
    Shelves,
    StatsBesideShelves,
}

/**
 * Home's sections, top to bottom (spec §2.1, §3.4). The slot is first when it shows; below it the order is always
 * Also in progress → Your last 7 days → My shelves. A wide page puts Resume beside the stats when nothing is loaded,
 * and the stats beside the shelves when a book is. Decided here and rendered in this order (C9), so the focus order
 * is the visual order.
 */
internal fun homeBlocks(
    slot: HomeSlot,
    hasAlsoInProgress: Boolean,
    isWide: Boolean,
): List<HomeBlock> =
    buildList {
        val isResume = slot is HomeSlot.Resume
        when {
            isResume && isWide -> add(HomeBlock.ResumeBesideStats)
            isResume -> add(HomeBlock.Resume)
            else -> interimSlotBlock(slot)?.let(::add)
        }
        if (hasAlsoInProgress) add(HomeBlock.AlsoInProgress)
        when {
            isResume && isWide -> {
                add(HomeBlock.Shelves)
            }

            isWide -> {
                add(HomeBlock.StatsBesideShelves)
            }

            else -> {
                add(HomeBlock.Stats)
                add(HomeBlock.Shelves)
            }
        }
    }

/**
 * Open question 5 (the window between Home PR 3 and PR 4), in one place: until PR 4 draws them, JustFinished and
 * FirstSync take no space, and NothingInProgress / NewListener show the interim empty state.
 */
private fun interimSlotBlock(slot: HomeSlot): HomeBlock? =
    when (slot) {
        is HomeSlot.NothingInProgress, is HomeSlot.NewListener -> HomeBlock.NothingToResume
        HomeSlot.Absent, HomeSlot.FirstSync, is HomeSlot.JustFinished, is HomeSlot.Resume -> null
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
