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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.calypsan.listenup.client.features.nowplaying.components.FloatingReaction
import com.calypsan.listenup.client.features.shell.components.NavigationBarHeight
import com.calypsan.listenup.client.playback.ContributorPickerType
import com.calypsan.listenup.client.playback.NowPlayingOverlay
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackProgress
import com.calypsan.listenup.client.presentation.campfire.CampfireScreenEvent
import com.calypsan.listenup.client.presentation.campfire.CampfireScreenUiState
import com.calypsan.listenup.client.presentation.campfire.CampfireViewModel
import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingNavAction
import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingViewModel
import com.calypsan.listenup.client.playback.SleepTimerState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.campfire_control_denied
import org.jetbrains.compose.resources.getString

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
    campfireViewModel: CampfireViewModel,
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

    // Campfire (co-listening) session mode — chrome only renders while a session is Active for
    // the book currently shown here (campfire implementation plan, Task 10).
    val campfire =
        rememberCampfireHost(
            campfireViewModel = campfireViewModel,
            playingState = screenState.state as? NowPlayingState.Active,
            snackbarHostState = snackbarHostState,
            progress = provideProgress,
        )

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
                    onPlayPause = campfire.playPause ?: viewModel::playPause,
                    onSeek = campfire.seek ?: viewModel::seekWithinChapter,
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
                    campfireSession = campfire.session,
                    onOpenCampfireChat = { campfire.setShowChat(true) },
                    floatingReactions = campfire.reactions,
                    onCampfireReactionFinished = campfire.onReactionFinished,
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

        CampfireOverlays(campfire = campfire, campfireViewModel = campfireViewModel)
    }
}

/**
 * Campfire wiring for [NowPlayingHost], extracted to keep the host inside the cognitive-complexity
 * budget. Carries the derived session (only when it matches the playing book), the transient
 * chat/reaction UI state, and the campfire-routed transport intents — `null` when no session is
 * active, so callers fall back to the plain player controls.
 */
private class CampfireHostUi(
    val state: CampfireScreenUiState,
    val session: CampfireScreenUiState.Active?,
    val reactions: List<FloatingReaction>,
    val onReactionFinished: (Long) -> Unit,
    val showChat: Boolean,
    val setShowChat: (Boolean) -> Unit,
    val dismissedRejoinStateVersion: Long?,
    val setDismissedRejoinStateVersion: (Long?) -> Unit,
    val playPause: (() -> Unit)?,
    val seek: ((Float) -> Unit)?,
)

/**
 * Collects [CampfireViewModel] state/events into a [CampfireHostUi] for [NowPlayingHost]: derives
 * the active session for the playing book, folds one-shot events (ControlDenied → snackbar,
 * ReactionReceived → floating overlay entry), and builds the campfire-routed play/pause + seek
 * intents. Only play/pause and seek funnel through the room — they are the surfaces
 * `PlaybackCommand` models (Play/Pause/SeekTo/SetSpeed); chapter skips stay local.
 */
@Composable
private fun rememberCampfireHost(
    campfireViewModel: CampfireViewModel,
    playingState: NowPlayingState.Active?,
    snackbarHostState: SnackbarHostState?,
    progress: () -> PlaybackProgress,
): CampfireHostUi {
    val campfireState by campfireViewModel.state.collectAsStateWithLifecycle()
    val session = (campfireState as? CampfireScreenUiState.Active)?.takeIf { it.bookId == playingState?.bookId }

    var showChat by remember { mutableStateOf(false) }
    LaunchedEffect(session == null) {
        if (session == null) showChat = false
    }

    val reactions = remember { mutableStateListOf<FloatingReaction>() }
    var dismissedRejoinStateVersion by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(campfireViewModel) {
        campfireViewModel.events.collect { event ->
            when (event) {
                CampfireScreenEvent.ControlDenied -> {
                    snackbarHostState?.showSnackbar(getString(Res.string.campfire_control_denied))
                }

                is CampfireScreenEvent.ReactionReceived -> {
                    reactions.add(FloatingReaction(id = System.nanoTime(), emoji = event.emoji))
                }
            }
        }
    }

    val isPlaying = playingState?.isPlaying == true
    return CampfireHostUi(
        state = campfireState,
        session = session,
        reactions = reactions,
        onReactionFinished = { id -> reactions.removeAll { it.id == id } },
        showChat = showChat,
        setShowChat = { showChat = it },
        dismissedRejoinStateVersion = dismissedRejoinStateVersion,
        setDismissedRejoinStateVersion = { dismissedRejoinStateVersion = it },
        playPause =
            session?.let {
                { if (isPlaying) campfireViewModel.pause() else campfireViewModel.play() }
            },
        seek =
            session?.let {
                { fraction ->
                    val current = progress()
                    val chapterStartMs = current.bookPositionMs - current.chapterPositionMs
                    campfireViewModel.seekTo(chapterStartMs + (current.chapterDurationMs * fraction).toLong())
                }
            },
    )
}

/**
 * Campfire session overlays — chat sheet, spoiler confirm, rejoin confirm. Independent of
 * [NowPlayingOverlay] (the plain player's own overlay enum) since a spoiler/rejoin prompt can
 * surface before the full-screen player is ever expanded.
 */
@Composable
private fun CampfireOverlays(
    campfire: CampfireHostUi,
    campfireViewModel: CampfireViewModel,
) {
    val session = campfire.session
    if (campfire.showChat && session != null) {
        CampfireChatSheet(
            messages = session.chat,
            members = session.members,
            onSend = campfireViewModel::sendChat,
            onReaction = campfireViewModel::sendReaction,
            onDismiss = { campfire.setShowChat(false) },
        )
    }

    if (campfire.state is CampfireScreenUiState.ConfirmingSpoiler) {
        CampfireSpoilerDialog(
            onConfirm = campfireViewModel::confirmSpoilerJoin,
            onCancel = campfireViewModel::cancelSpoilerJoin,
        )
    }

    val pendingRejoin = (campfire.state as? CampfireScreenUiState.Active)?.pendingRejoinSync
    if (pendingRejoin != null && pendingRejoin.stateVersion != campfire.dismissedRejoinStateVersion) {
        CampfireRejoinDialog(
            onConfirm = campfireViewModel::confirmRejoinSync,
            onDismiss = { campfire.setDismissedRejoinStateVersion(pendingRejoin.stateVersion) },
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
