package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.device.DeviceType
import com.calypsan.listenup.client.features.shell.components.NavigationBarHeight
import com.calypsan.listenup.client.playback.ContributorPickerType
import com.calypsan.listenup.client.playback.NowPlayingOverlay
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingViewModel
import com.calypsan.listenup.client.playback.SleepTimerState

/** Height of a standard snackbar for padding calculations */
private val SnackbarHeight = 48.dp

/**
 * Container that manages both NowPlayingBar and NowPlayingScreen.
 *
 * Handles expand/collapse animation between mini player and full screen.
 * Should be placed in the app shell, rendered above other content.
 *
 * Uses spring animations for natural, physical-feeling motion as per M3 Expressive.
 */
@Suppress("LongMethod")
@Composable
fun NowPlayingHost(
    hasBottomNav: Boolean,
    snackbarHostState: SnackbarHostState?,
    onNavigateToBook: (String) -> Unit,
    onNavigateToSeries: (String) -> Unit,
    onNavigateToContributor: (String) -> Unit,
    viewModel: NowPlayingViewModel,
    modifier: Modifier = Modifier,
) {
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val isSnackbarVisible = snackbarHostState?.currentSnackbarData != null

    val deviceContext = LocalDeviceContext.current
    val useDockedBar = deviceContext.type in setOf(DeviceType.Tv, DeviceType.Desktop, DeviceType.Tablet)
    val isTv = deviceContext.isLeanback

    val state = screenState.state
    val activeState = state as? NowPlayingState.Active

    Box(modifier = modifier.fillMaxSize()) {
        // Full screen (slides up when expanded). Only renders when we have an Active book —
        // expanding into Idle/Error has no meaningful UI.
        AnimatedVisibility(
            visible = screenState.isExpanded && activeState != null,
            enter =
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                ) + fadeIn(),
            exit =
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                ) + fadeOut(),
        ) {
            if (activeState != null) {
                NowPlayingScreen(
                    state = activeState,
                    sleepTimerState = screenState.sleepTimerState,
                    onCollapse = viewModel::collapse,
                    onPlayPause = viewModel::playPause,
                    onSeek = viewModel::seekWithinChapter,
                    onSkipBack = { viewModel.skipBack() },
                    onSkipForward = { viewModel.skipForward() },
                    onPreviousChapter = viewModel::previousChapter,
                    onNextChapter = viewModel::nextChapter,
                    onSpeedClick = viewModel::showSpeedPicker,
                    onChaptersClick = viewModel::showChapterPicker,
                    onSleepTimerClick = viewModel::showSleepTimer,
                    onGoToBook = {
                        viewModel.collapse()
                        onNavigateToBook(activeState.bookId)
                    },
                    onGoToSeries = { seriesId ->
                        viewModel.collapse()
                        onNavigateToSeries(seriesId)
                    },
                    onGoToContributor = { contributorId ->
                        viewModel.collapse()
                        onNavigateToContributor(contributorId)
                    },
                    onShowAuthorPicker = { viewModel.showContributorPicker(ContributorPickerType.AUTHORS) },
                    onShowNarratorPicker = { viewModel.showContributorPicker(ContributorPickerType.NARRATORS) },
                    onCloseBook = viewModel::closeBook,
                    isTv = isTv,
                )
            }
        }

        // Mini player — docked bar for TV/Desktop/Tablet, floating pill for phone
        if (useDockedBar) {
            DockedNowPlayingBar(
                state = state,
                isExpanded = screenState.isExpanded,
                onTap = viewModel::expand,
                onPlayPause = viewModel::playPause,
                onSkipBack = { viewModel.skipBack() },
                onSkipForward = { viewModel.skipForward() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else if (!hasBottomNav) {
            // Only render floating mini bar on detail screens (not Shell)
            // When hasBottomNav=true, the mini bar is rendered inside AppShell's bottomBar
            val navBarInsets = WindowInsets.navigationBars
            val density = LocalDensity.current
            val systemNavBarHeight = with(density) { navBarInsets.getBottom(density).toDp() }
            val snackbarPadding = if (isSnackbarVisible) SnackbarHeight + 8.dp else 0.dp
            val targetBottomPadding =
                if (hasBottomNav) {
                    NavigationBarHeight + systemNavBarHeight + snackbarPadding
                } else {
                    systemNavBarHeight + 8.dp + snackbarPadding
                }
            val bottomPadding by animateDpAsState(
                targetValue = targetBottomPadding,
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                label = "miniPlayerPosition",
            )

            NowPlayingBar(
                state = state,
                isExpanded = screenState.isExpanded,
                onTap = viewModel::expand,
                onPlayPause = viewModel::playPause,
                onSkipBack = { viewModel.skipBack() },
                onSkipForward = { viewModel.skipForward() },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomPadding),
            )
        }

        OverlayDispatch(
            overlay = screenState.overlay,
            sleepTimerState = screenState.sleepTimerState,
            activeState = activeState,
            viewModel = viewModel,
            onNavigateToContributor = onNavigateToContributor,
        )
    }
}

@Composable
private fun OverlayDispatch(
    overlay: NowPlayingOverlay,
    sleepTimerState: SleepTimerState,
    activeState: NowPlayingState.Active?,
    viewModel: NowPlayingViewModel,
    onNavigateToContributor: (String) -> Unit,
) {
    when (overlay) {
        NowPlayingOverlay.None -> { /* no overlay */ }

        NowPlayingOverlay.ChapterPicker -> {
            if (activeState != null) {
                ChapterPickerSheet(
                    chapters = viewModel.getChapters(),
                    currentChapterIndex = activeState.chapterIndex,
                    onChapterSelected = viewModel::seekToChapter,
                    onDismiss = viewModel::hideChapterPicker,
                )
            }
        }

        NowPlayingOverlay.SleepTimer -> {
            SleepTimerSheet(
                currentState = sleepTimerState,
                onSetTimer = viewModel::setSleepTimer,
                onCancelTimer = viewModel::cancelSleepTimer,
                onExtendTimer = viewModel::extendSleepTimer,
                onDismiss = viewModel::hideSleepTimer,
            )
        }

        is NowPlayingOverlay.ContributorPicker -> {
            if (activeState != null) {
                val contributors =
                    when (overlay.type) {
                        ContributorPickerType.AUTHORS -> activeState.authors
                        ContributorPickerType.NARRATORS -> activeState.narrators
                    }
                ContributorPickerSheet(
                    type = overlay.type,
                    contributors = contributors,
                    onContributorSelected = { contributorId ->
                        viewModel.hideContributorPicker()
                        viewModel.collapse()
                        onNavigateToContributor(contributorId)
                    },
                    onDismiss = viewModel::hideContributorPicker,
                )
            }
        }

        NowPlayingOverlay.SpeedPicker -> {
            if (activeState != null) {
                PlaybackSpeedSheet(
                    currentSpeed = activeState.playbackSpeed,
                    defaultSpeed = activeState.defaultPlaybackSpeed,
                    onSpeedChange = viewModel::setSpeed,
                    onResetToDefault = viewModel::resetSpeedToDefault,
                    onDismiss = viewModel::hideSpeedPicker,
                )
            }
        }
    }
}
