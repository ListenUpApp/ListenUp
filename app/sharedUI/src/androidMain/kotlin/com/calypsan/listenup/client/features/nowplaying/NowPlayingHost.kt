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
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.features.contributors.CastRole
import com.calypsan.listenup.client.features.contributors.FullCastSheetFor
import com.calypsan.listenup.client.features.shell.components.NavigationBarHeight
import com.calypsan.listenup.client.playback.ContributorPickerType
import com.calypsan.listenup.client.playback.NowPlayingOverlay
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingNavAction
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
    onNavigateToDocument: (localPath: String) -> Unit,
    viewModel: NowPlayingViewModel,
    onBarFootprintChanged: (Dp) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val progressState = viewModel.progress.collectAsStateWithLifecycle()
    // Pass progress as a deferred lambda so the 4 Hz position tick only recomposes the leaf
    // scrubber + time labels that actually read it — never this host or the player chrome.
    val provideProgress = { progressState.value }
    val firstPdfDocId by viewModel.firstPdfDocId.collectAsStateWithLifecycle()

    // Consume one-shot navigation events from the ViewModel.
    LaunchedEffect(viewModel) {
        viewModel.navActions.collect { action ->
            when (action) {
                is NowPlayingNavAction.OpenDocumentViewer -> onNavigateToDocument(action.localPath)
            }
        }
    }
    val isSnackbarVisible = snackbarHostState?.currentSnackbarData != null

    val deviceContext = LocalDeviceContext.current
    val isTv = deviceContext.isLeanback
    // Pick the bar by the *live* window width, not a static device label: a foldable folded to its
    // cover screen is compact and should get the slim floating bar, not the wide docked one. TV always
    // docks (leanback never shows the floating pill).
    val useDockedBar =
        isTv ||
            currentWindowAdaptiveInfo()
                .windowSizeClass
                .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

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
                    progress = provideProgress,
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
                    hasPdf = firstPdfDocId != null,
                    onOpenPdf = viewModel::onOpenCurrentPdf,
                    isTv = isTv,
                )
            }
        }

        // Mini player — docked bar for TV/Desktop/Tablet, floating pill for phone
        if (useDockedBar) {
            DockedNowPlayingBar(
                state = state,
                progress = provideProgress,
                isExpanded = screenState.isExpanded,
                onTap = viewModel::expand,
                onPlayPause = viewModel::playPause,
                onSkipBack = { viewModel.skipBack() },
                onSkipForward = { viewModel.skipForward() },
                onSeek = viewModel::seekWithinChapter,
                onSpeedClick = viewModel::showSpeedPicker,
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
                progress = provideProgress,
                isExpanded = screenState.isExpanded,
                onTap = viewModel::expand,
                onPlayPause = viewModel::playPause,
                onSkipBack = { viewModel.skipBack() },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { size ->
                            // Report the bar's FULL resting footprint — its own height *plus* the
                            // nav-bar + 8dp offset it floats above — by measuring outside the
                            // .padding below. LocalNowPlayingInsets must span from the screen bottom
                            // to the bar's top edge so ListenUpScaffold's union with the system bars
                            // clears a docked bottomBar (e.g. Save Changes); measuring inside the
                            // padding under-reported by exactly that offset and let the bar overlap.
                            // A visible snackbar lifts the bar (snackbarPadding) — reporting that
                            // would reflow every detail screen up for the snackbar's duration, so we
                            // skip those measurements.
                            if (!isSnackbarVisible) {
                                onBarFootprintChanged(with(density) { size.height.toDp() })
                            }
                        }.padding(bottom = bottomPadding),
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
                    chapters = viewModel.chapters,
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
                val role =
                    when (overlay.type) {
                        ContributorPickerType.AUTHORS -> CastRole.Authors
                        ContributorPickerType.NARRATORS -> CastRole.Narrators
                    }
                FullCastSheetFor(
                    role = role,
                    authors = activeState.authors,
                    narrators = activeState.narrators,
                    onContributorClick = { contributorId ->
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
