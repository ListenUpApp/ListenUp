package com.calypsan.listenup.client.features.home

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.presentation.home.HomeSlot

/** How much width Home has (C6): phones, medium windows (600–839dp) and tablets (≥ 840dp, spec §3.4). */
internal enum class HomeWidth { Compact, Medium, Expanded }

/** Home's width class, from the window (sharedUI rule 11). */
@Composable
internal fun rememberHomeWidth(): HomeWidth {
    val sizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    return when {
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> HomeWidth.Expanded
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> HomeWidth.Medium
        else -> HomeWidth.Compact
    }
}

/** One section of Home, in the order [homeBlocks] puts them. */
internal enum class HomeBlock {
    /** The quiet offline pill, directly under the greeting (spec §3.1). */
    Offline,

    /** "Building your library", above the first-sync skeleton (spec §2.3 FirstSync, H5). */
    FirstSyncStrip,
    Finished,
    NothingInProgress,
    Welcome,

    /** The skeleton slot that resolves in place (spec §2.1 rule 4). */
    FirstSync,

    /** Continue Listening could not load: one line with Try again, in the slot's position (spec §3.3). */
    ContinueListeningFailed,

    /** Whichever slot applies, beside the stats on a tablet (spec §3.4). */
    SlotBesideStats,
    EverythingInProgress,
    Stats,
    Shelves,
    StatsBesideShelves,
}

/**
 * Home's sections, top to bottom (spec §2.1, §3.4). The offline pill sits under the greeting ([isOffline]), then the
 * library-arriving strip ([showsStrip], spec H5), then the slot — or, when Continue Listening failed and there is no
 * slot, the one-line failure in its place. Offline and the failure are separate signals, so both can show. Below the
 * slot the order is always Everything in progress → Your last 7 days → My shelves. A tablet puts the slot beside the stats,
 * and the stats beside the shelves when there is no slot.
 */
internal fun homeBlocks(
    slot: HomeSlot,
    isOffline: Boolean,
    continueListeningFailed: Boolean,
    showsStrip: Boolean,
    hasEverythingInProgress: Boolean,
    width: HomeWidth,
): List<HomeBlock> =
    buildList {
        val wide = width == HomeWidth.Expanded
        if (isOffline) add(HomeBlock.Offline)
        if (showsStrip) add(HomeBlock.FirstSyncStrip)
        val slotBlock = slotBlockFor(slot)
        when {
            slotBlock != null && wide -> add(HomeBlock.SlotBesideStats)
            slotBlock != null -> add(slotBlock)
            continueListeningFailed -> add(HomeBlock.ContinueListeningFailed)
        }
        if (hasEverythingInProgress) add(HomeBlock.EverythingInProgress)
        when {
            contains(HomeBlock.SlotBesideStats) -> {
                add(HomeBlock.Shelves)
            }

            wide -> {
                add(HomeBlock.StatsBesideShelves)
            }

            else -> {
                add(HomeBlock.Stats)
                add(HomeBlock.Shelves)
            }
        }
    }

/** The block that draws [slot], or null when there is no slot. */
internal fun slotBlockFor(slot: HomeSlot): HomeBlock? =
    when (slot) {
        is HomeSlot.JustFinished -> HomeBlock.Finished
        is HomeSlot.NothingInProgress -> HomeBlock.NothingInProgress
        is HomeSlot.NewListener -> HomeBlock.Welcome
        HomeSlot.FirstSync -> HomeBlock.FirstSync
        HomeSlot.Absent -> null
    }

/** What Home can ask the shell to do. All navigation leaves as one of these. */
internal data class HomeActions(
    val onOpenBook: (String) -> Unit,
    val onPlay: (String) -> Unit,
    val onOpenLibrary: () -> Unit,
    val onOpenLeaderboard: () -> Unit,
    val onOpenShelf: (String) -> Unit,
    val onCreateShelf: () -> Unit,
    /** Opens Library filtered to In progress (Everything in progress's "In Library", spec §6.4). */
    val onOpenInProgress: () -> Unit = {},
    /** Opens Discover (the quiet slots' second button). */
    val onOpenDiscover: () -> Unit = {},
    /** Dismisses the finished card for a book on every device. */
    val onDismissFinished: (String) -> Unit = {},
    /** Rates a finished book on the 2..10 half-star scale. */
    val onRateFinished: (String, Int) -> Unit = { _, _ -> },
    /** Opens a book with its readers in view (the finishers line). */
    val onOpenReaders: (String) -> Unit = {},
    /** Tries Home again: the page error and the Continue Listening line. */
    val onRetry: () -> Unit = {},
    /** Opens the My shelves list; null where the host has no list, so See all is not offered. */
    val onSeeAllShelves: (() -> Unit)? = null,
) {
    companion object {
        /** For previews and tests that press nothing. */
        val None =
            HomeActions(
                onOpenBook = {},
                onPlay = {},
                onOpenLibrary = {},
                onOpenLeaderboard = {},
                onOpenShelf = {},
                onCreateShelf = {},
            )
    }
}

/** Multi-select as Home's cards see it (H16, unchanged behaviour). */
internal data class HomeSelection(
    val isActive: Boolean,
    val selectedIds: Set<String>,
    val onToggle: (String) -> Unit,
    val onEnter: (String) -> Unit,
) {
    companion object {
        /** Nothing selected and no way to select: previews and tests. */
        val None = HomeSelection(isActive = false, selectedIds = emptySet(), onToggle = {}, onEnter = {})
    }
}
