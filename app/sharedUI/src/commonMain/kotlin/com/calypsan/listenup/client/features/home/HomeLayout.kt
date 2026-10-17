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
 * Also in progress → Your last 7 days → My shelves. A tablet puts Resume beside the stats when nothing is loaded, and
 * the stats beside the shelves when a book is.
 */
internal fun homeBlocks(
    slot: HomeSlot,
    hasAlsoInProgress: Boolean,
    width: HomeWidth,
): List<HomeBlock> =
    buildList {
        val isResume = slot is HomeSlot.Resume
        val wide = width == HomeWidth.Expanded
        when {
            isResume && wide -> add(HomeBlock.ResumeBesideStats)
            isResume -> add(HomeBlock.Resume)
            else -> interimSlotBlock(slot)?.let(::add)
        }
        if (hasAlsoInProgress) add(HomeBlock.AlsoInProgress)
        when {
            isResume && wide -> {
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

/**
 * Open question 5 (the window between Home PR 3 and PR 4), in one place: until PR 4 draws them, JustFinished and
 * FirstSync take no space, and NothingInProgress / NewListener show the interim empty state.
 */
private fun interimSlotBlock(slot: HomeSlot): HomeBlock? =
    when (slot) {
        is HomeSlot.NothingInProgress, is HomeSlot.NewListener -> HomeBlock.NothingToResume
        HomeSlot.Absent, HomeSlot.FirstSync, is HomeSlot.JustFinished, is HomeSlot.Resume -> null
    }

/** What Home can ask the shell to do. All navigation leaves as one of these. */
internal data class HomeActions(
    val onOpenBook: (String) -> Unit,
    val onPlay: (String) -> Unit,
    val onOpenLibrary: () -> Unit,
    val onOpenLeaderboard: () -> Unit,
    val onOpenShelf: (String) -> Unit,
    val onCreateShelf: () -> Unit,
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
