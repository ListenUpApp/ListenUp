package com.calypsan.listenup.client.features.home

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowSizeClass

/** How much width Home has: phones, medium windows (600–839dp) and tablets (≥ 840dp). */
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

/** What Home can ask the shell to do. All navigation leaves as one of these. */
internal data class HomeActions(
    val onOpenBook: (String) -> Unit,
    val onPlay: (String) -> Unit,
    val onOpenLibrary: () -> Unit,
    val onOpenLeaderboard: () -> Unit,
    val onOpenShelf: (String) -> Unit,
    val onCreateShelf: () -> Unit,
    /** Opens Library filtered to In progress (Everything in progress's "In Library"). */
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

/** Multi-select as Home's cards see it (unchanged behaviour). */
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
