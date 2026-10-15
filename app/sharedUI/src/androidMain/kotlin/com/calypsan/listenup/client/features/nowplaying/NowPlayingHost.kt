package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
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
import com.calypsan.listenup.client.presentation.player.ContributorPickerType
import com.calypsan.listenup.client.presentation.player.PlayerOverlay
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.PlayerEvent
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PlayerViewModel

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
    onOpenSignIn: () -> Unit,
    onOpenInbox: () -> Unit,
    viewModel: PlayerViewModel,
    onBarFootprintChanged: (Dp) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val overlay by viewModel.overlay.collectAsStateWithLifecycle()
    val isExpanded by viewModel.expanded.collectAsStateWithLifecycle()
    val progressState = viewModel.progress.collectAsStateWithLifecycle()
    // Pass progress as a deferred lambda so the 4 Hz position tick only recomposes the leaf
    // scrubber + time labels that actually read it — never this host or the player chrome.
    val provideProgress = { progressState.value }

    // Consume one-shot navigation events from the ViewModel.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PlayerEvent.OpenDocumentViewer -> {
                    onNavigateToDocument(event.localPath)
                }

                is PlayerEvent.NavigateToBook -> {
                    viewModel.collapse()
                    onNavigateToBook(event.bookId)
                }

                PlayerEvent.OpenSignIn -> {
                    onOpenSignIn()
                }

                PlayerEvent.OpenInbox -> {
                    viewModel.collapse()
                    onOpenInbox()
                }
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
            currentWindowAdaptiveInfoV2()
                .windowSizeClass
                .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    val loaded = state as? PlayerUiState.Loaded
    // The user's configured transport skips — they drive the icon and the content description as
    // well as the seek, so the button can never claim a number the press does not honour.
    val skipBackwardSec = loaded?.run { settings.skipBackSec } ?: 0
    val skipForwardSec = loaded?.run { settings.skipForwardSec } ?: 0
    val motion = MaterialTheme.motionScheme

    Box(modifier = modifier.fillMaxSize()) {
        // Full screen (slides up when expanded). Only renders when a book is loaded — expanding
        // into nothing has no meaningful UI.
        AnimatedVisibility(
            visible = isExpanded && loaded != null,
            enter =
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = motion.slowSpatialSpec(),
                ) + fadeIn(motion.defaultEffectsSpec()),
            exit =
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = motion.fastSpatialSpec(),
                ) + fadeOut(motion.fastEffectsSpec()),
        ) {
            if (loaded != null) {
                NowPlayingScreen(
                    state = loaded,
                    progress = provideProgress,
                    onCollapse = viewModel::collapse,
                    onPrimary = viewModel::onPrimary,
                    onSeek = viewModel::seekWithinChapter,
                    onSkipBack = { viewModel.skipBack() },
                    onSkipForward = { viewModel.skipForward() },
                    onPreviousChapter = viewModel::previousChapter,
                    onNextChapter = viewModel::nextChapter,
                    onSpeedClick = viewModel::showSpeedPicker,
                    onBoostClick = viewModel::showBoostPicker,
                    onChaptersClick = viewModel::showChapterPicker,
                    onSleepTimerClick = viewModel::showSleepTimer,
                    onGoToBook = viewModel::goToBook,
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
                    skipBackwardSec = skipBackwardSec,
                    skipForwardSec = skipForwardSec,
                    hasPdf = loaded.book.firstPdfDocId != null,
                    onOpenPdf = viewModel::openCurrentPdf,
                    isTv = isTv,
                )
            }
        }

        // Mini player — docked bar for TV/Desktop/Tablet, floating pill for phone
        if (useDockedBar) {
            DockedNowPlayingBar(
                state = state,
                progress = provideProgress,
                isExpanded = isExpanded,
                onTap = viewModel::expand,
                onPrimary = viewModel::onPrimary,
                onSkipBack = { viewModel.skipBack() },
                onSkipForward = { viewModel.skipForward() },
                onSeek = viewModel::seekWithinChapter,
                onSpeedClick = viewModel::showSpeedPicker,
                skipBackwardSec = skipBackwardSec,
                skipForwardSec = skipForwardSec,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else if (!hasBottomNav) {
            // Detail screens: no navigation bar under it, so the docked bar sits at the bottom edge and runs under the
            // gesture area, padding its own row clear of it. Its measured footprint therefore already includes that
            // inset, which is what LocalNowPlayingInsets must span for ListenUpScaffold to clear it. (On the shell the
            // bar lives in AppShell's bottomBar, above the navigation bar, which pads the tab content itself.)
            val snackbarPadding = if (isSnackbarVisible) SnackbarHeight + 8.dp else 0.dp
            val bottomPadding by animateDpAsState(
                targetValue = snackbarPadding,
                // Matches ProvideNowPlayingInsets, so content clears the bar in step with it.
                animationSpec = motion.defaultSpatialSpec(),
                label = "miniPlayerPosition",
            )
            val density = LocalDensity.current
            NowPlayingBar(
                state = state,
                progress = provideProgress,
                isExpanded = isExpanded,
                onTap = viewModel::expand,
                onPrimary = viewModel::onPrimary,
                onSkipBack = { viewModel.skipBack() },
                onKeepListening = viewModel::cancelSleepTimer,
                skipBackwardSec = skipBackwardSec,
                bottomInset = WindowInsets.navigationBars,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { size ->
                            // Measured outside the snackbar lift below. A visible snackbar lifts the bar; reporting
                            // that would reflow every detail screen for the snackbar's lifetime, so those
                            // measurements are skipped.
                            if (!isSnackbarVisible) {
                                onBarFootprintChanged(with(density) { size.height.toDp() })
                            }
                        }.padding(bottom = bottomPadding),
            )
        }

        OverlayDispatch(
            overlay = overlay,
            sleepTimerState = loaded?.sleepTimer ?: SleepTimerState.Inactive,
            loaded = loaded,
            viewModel = viewModel,
            onNavigateToContributor = onNavigateToContributor,
        )
    }
}

@Composable
private fun OverlayDispatch(
    overlay: PlayerOverlay,
    sleepTimerState: SleepTimerState,
    loaded: PlayerUiState.Loaded?,
    viewModel: PlayerViewModel,
    onNavigateToContributor: (String) -> Unit,
) {
    when (overlay) {
        PlayerOverlay.None -> { /* no overlay */ }

        PlayerOverlay.ChapterPicker -> {
            if (loaded != null) {
                ChapterPickerSheet(
                    chapters = loaded.chapters,
                    onChapterSelected = viewModel::seekToChapter,
                    onDismiss = viewModel::hideOverlay,
                )
            }
        }

        PlayerOverlay.SleepTimer -> {
            SleepTimerSheet(
                currentState = sleepTimerState,
                onSetTimer = viewModel::setSleepTimer,
                onCancelTimer = viewModel::cancelSleepTimer,
                onExtendTimer = viewModel::extendSleepTimer,
                onDismiss = viewModel::hideOverlay,
            )
        }

        is PlayerOverlay.ContributorPicker -> {
            if (loaded != null) {
                val role =
                    when (overlay.type) {
                        ContributorPickerType.AUTHORS -> CastRole.Authors
                        ContributorPickerType.NARRATORS -> CastRole.Narrators
                    }
                FullCastSheetFor(
                    role = role,
                    authors = loaded.book.authors.asBookContributors(),
                    narrators = loaded.book.narrators.asBookContributors(),
                    onContributorClick = { contributorId ->
                        viewModel.collapse()
                        onNavigateToContributor(contributorId)
                    },
                    onDismiss = viewModel::hideOverlay,
                )
            }
        }

        PlayerOverlay.SpeedPicker -> {
            if (loaded != null) {
                PlaybackSpeedSheet(
                    currentSpeed = loaded.settings.speed,
                    defaultSpeed = loaded.settings.defaultSpeed,
                    onSpeedChange = viewModel::setSpeed,
                    onResetToDefault = viewModel::resetSpeed,
                    onDismiss = viewModel::hideOverlay,
                )
            }
        }

        PlayerOverlay.BoostPicker -> {
            if (loaded != null) {
                VolumeBoostSheet(
                    currentBoostDb = loaded.settings.boostDb,
                    defaultBoostDb = loaded.settings.defaultBoostDb,
                    onBoostChange = viewModel::setBoost,
                    onResetToDefault = viewModel::resetBoost,
                    onDismiss = viewModel::hideOverlay,
                )
            }
        }
    }
}
