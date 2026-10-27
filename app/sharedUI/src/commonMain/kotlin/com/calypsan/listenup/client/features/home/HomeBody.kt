package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.InlineRetry
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.home.components.HomeHeader
import com.calypsan.listenup.client.features.shell.components.AppHeaderSlot
import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_couldnt_load_listening
import org.jetbrains.compose.resources.stringResource

/** The stats card's width beside shelves or Resume on a tablet (spec §3.4). */
private val WideStatsWidth = 400.dp

/**
 * Home, drawn from fixed state (C12): the greeting in the shell header, then [homeBlocks]. No ViewModel, no Koin, so
 * host tests render the real screen.
 */
@Composable
internal fun HomeBody(
    state: HomeUiState.Ready,
    stats: HomeStatsUiState,
    width: HomeWidth,
    actions: HomeActions,
    selection: HomeSelection,
    appHeader: AppHeaderSlot,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onRetryStats: () -> Unit = {},
) {
    // The shell's system-bar/nav insets are applied *inside* the scroll so content scrolls edge-to-edge under the
    // bars and rests clear of them.
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
    ) {
        // The shell header scrolls away with the page; the greeting is its leading content.
        appHeader {
            HomeHeader(
                timeOfDay = state.timeOfDay,
                firstName = state.firstName,
                isWide = width != HomeWidth.Compact,
                isWelcome = state.slot is HomeSlot.NewListener,
            )
        }
        homeBlocks(
            slot = state.slot,
            isOffline = state.isOffline,
            continueListeningFailed = state.continueListeningError != null,
            showsStrip = state.showsArrivalStrip,
            hasAlsoInProgress = state.everythingInProgress.isNotEmpty(),
            width = width,
        ).forEach { block ->
            HomeBlockContent(
                block = block,
                state = state,
                stats = stats,
                width = width,
                actions = actions,
                selection = selection,
                onRetryStats = onRetryStats,
            )
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}

/** One block of [homeBlocks], drawn. */
@Composable
private fun HomeBlockContent(
    block: HomeBlock,
    state: HomeUiState.Ready,
    stats: HomeStatsUiState,
    width: HomeWidth,
    actions: HomeActions,
    selection: HomeSelection,
    onRetryStats: () -> Unit,
) {
    val margin = Modifier.padding(horizontal = Spacing.screenMargin)
    when (block) {
        HomeBlock.Resume -> {
            ResumeHero(state, actions, margin)
        }

        HomeBlock.SlotBesideStats -> {
            Row(margin, horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                SlotContent(state, actions, Modifier.weight(1f))
                WideStats(stats, actions, onRetryStats)
            }
        }

        HomeBlock.Finished, HomeBlock.NothingInProgress, HomeBlock.Welcome, HomeBlock.FirstSync -> {
            SlotContent(state, actions, margin)
        }

        HomeBlock.FirstSyncStrip -> {
            FirstSyncStrip(state.scanProgress, margin)
        }

        HomeBlock.Offline -> {
            OfflinePill(margin)
        }

        HomeBlock.ContinueListeningFailed -> {
            InlineRetry(
                line = stringResource(Res.string.home_couldnt_load_listening),
                onRetry = actions.onRetry,
                modifier = margin,
            )
        }

        HomeBlock.AlsoInProgress -> {
            AlsoInProgressSection(
                items = state.everythingInProgress,
                width = width,
                selection = selection,
                onOpenBook = actions.onOpenBook,
                onOpenInProgress = actions.onOpenInProgress,
                connectivity = state.connectivity,
            )
        }

        HomeBlock.Stats -> {
            HomeStatsCard(
                stats = stats,
                isWide = false,
                onOpenLeaderboard = actions.onOpenLeaderboard,
                onRetry = onRetryStats,
                modifier = margin,
            )
        }

        HomeBlock.Shelves -> {
            MyShelvesSection(
                shelves = state.myShelves,
                isLoading = state.showsShelvesLoading,
                width = width,
                onOpenShelf = actions.onOpenShelf,
                onCreateShelf = actions.onCreateShelf,
                onSeeAll = actions.onSeeAllShelves,
            )
        }

        HomeBlock.StatsBesideShelves -> {
            Row(margin, horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                WideStats(stats, actions, onRetryStats)
                MyShelvesSection(
                    shelves = state.myShelves,
                    isLoading = state.showsShelvesLoading,
                    width = width,
                    onOpenShelf = actions.onOpenShelf,
                    onCreateShelf = actions.onCreateShelf,
                    onSeeAll = actions.onSeeAllShelves,
                    modifier = Modifier.weight(1f),
                    horizontalMargin = 0.dp,
                )
            }
        }
    }
}

/** Whichever slot applies, drawn: every kind takes the same position (spec §2.1). */
@Composable
private fun SlotContent(
    state: HomeUiState.Ready,
    actions: HomeActions,
    modifier: Modifier,
) {
    when (val slot = state.slot) {
        is HomeSlot.Resume -> ResumeHero(state, actions, modifier)
        is HomeSlot.NothingInProgress -> HomeNothingInProgress(slot, actions, modifier)
        is HomeSlot.NewListener -> HomeWelcome(slot, actions, modifier)
        is HomeSlot.JustFinished -> HomeFinishedCard(slot.card, actions, modifier)
        HomeSlot.FirstSync -> HomeSkeletonSlot(withResume = true, modifier = modifier)
        HomeSlot.Absent -> Unit
    }
}

@Composable
private fun ResumeHero(
    state: HomeUiState.Ready,
    actions: HomeActions,
    modifier: Modifier,
) {
    val resume = state.slot as? HomeSlot.Resume ?: return
    HomeResumeHero(
        resume = resume,
        isOnlyBook = state.isOnlyBookInProgress,
        onOpenBook = actions.onOpenBook,
        onResume = actions.onPlay,
        modifier = modifier,
    )
}

@Composable
private fun WideStats(
    stats: HomeStatsUiState,
    actions: HomeActions,
    onRetryStats: () -> Unit,
) {
    HomeStatsCard(
        stats = stats,
        isWide = true,
        onOpenLeaderboard = actions.onOpenLeaderboard,
        onRetry = onRetryStats,
        modifier = Modifier.width(WideStatsWidth),
    )
}
