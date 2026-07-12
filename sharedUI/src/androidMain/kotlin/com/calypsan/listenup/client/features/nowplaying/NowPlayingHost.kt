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
import com.calypsan.listenup.api.dto.campfire.CampfirePhase
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.features.campfire.CampfireFeedRow
import com.calypsan.listenup.client.features.campfire.CampfireFlowBook
import com.calypsan.listenup.client.features.campfire.CampfireInviteScreen
import com.calypsan.listenup.client.features.campfire.CampfireLobbyScreen
import com.calypsan.listenup.client.features.campfire.CampfireRoomScreen
import com.calypsan.listenup.client.features.campfire.rememberCampfireFeed
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
import kotlinx.coroutines.flow.flowOf
import org.koin.compose.koinInject

/** Height of a standard snackbar for padding calculations */
private val SnackbarHeight = 48.dp

// Room screen skip-back/forward increments — mirrors the plain player's Replay10/Forward30 controls.
private const val SKIP_BACK_MS = 10_000L
private const val SKIP_FORWARD_MS = 30_000L

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
        // expanding into Idle/Error has no meaningful UI. A Campfire session for this book takes
        // over unconditionally (Lobby/Room are the full-screen Campfire experience, task L3) —
        // independent of screenState.isExpanded, since a just-created/joined session should land
        // full-screen immediately rather than waiting for the ordinary mini-player expand gesture.
        AnimatedVisibility(
            visible = (screenState.isExpanded && activeState != null) || campfire.session != null,
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
            NowPlayingFullScreenContent(
                campfire = campfire,
                campfireViewModel = campfireViewModel,
                activeState = activeState,
                isTv = isTv,
                hasPdf = firstPdfDocId != null,
                provideProgress = provideProgress,
                viewModel = viewModel,
                onNavigateToBook = onNavigateToBook,
                onNavigateToSeries = onNavigateToSeries,
                onNavigateToContributor = onNavigateToContributor,
            )
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
 * The full-screen content [NowPlayingHost] slides up: a Campfire Lobby/Room (task L3, whenever
 * [CampfireHostUi.session] is non-null) or the plain [NowPlayingScreen] otherwise. Extracted from
 * [NowPlayingHost] to keep it inside the cognitive-complexity budget.
 */
@Suppress("LongParameterList")
@Composable
private fun NowPlayingFullScreenContent(
    campfire: CampfireHostUi,
    campfireViewModel: CampfireViewModel,
    activeState: NowPlayingState.Active?,
    isTv: Boolean,
    hasPdf: Boolean,
    provideProgress: () -> PlaybackProgress,
    viewModel: NowPlayingViewModel,
    onNavigateToBook: (String) -> Unit,
    onNavigateToSeries: (String) -> Unit,
    onNavigateToContributor: (String) -> Unit,
) {
    val session = campfire.session
    val book = campfire.book
    if (session != null && book != null) {
        CampfireLobbyOrRoomContent(
            session = session,
            book = book,
            campfire = campfire,
            campfireViewModel = campfireViewModel,
            activeState = activeState,
            provideProgress = provideProgress,
        )
    } else if (activeState != null) {
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
            hasPdf = hasPdf,
            onOpenPdf = viewModel::onOpenCurrentPdf,
            isTv = isTv,
        )
    }
}

/** The Campfire branch of [NowPlayingFullScreenContent] — dispatches [session]'s phase to Lobby or Room/Invite. */
@Composable
private fun CampfireLobbyOrRoomContent(
    session: CampfireScreenUiState.Active,
    book: CampfireFlowBook,
    campfire: CampfireHostUi,
    campfireViewModel: CampfireViewModel,
    activeState: NowPlayingState.Active?,
    provideProgress: () -> PlaybackProgress,
) {
    when (session.phase) {
        CampfirePhase.LOBBY -> {
            CampfireLobbyScreen(
                campfireName = session.name,
                bookTitle = book.title,
                members = session.members,
                invitedPending = session.invitedPending,
                hostUserId = session.hostUserId,
                hostDisplayName = session.hostDisplayName,
                isHost = session.isHost,
                onStart = campfireViewModel::startCampfire,
            )
        }

        CampfirePhase.LIVE -> {
            if (campfire.showInvite) {
                val inviteState by campfireViewModel.inviteState.collectAsStateWithLifecycle()
                CampfireInviteScreen(
                    inviteState = inviteState,
                    excludedUserIds =
                        (session.members.map { it.userId } + session.invitedPending.map { it.userId }).toSet(),
                    onLoadInvitableUsers = { campfireViewModel.listInvitableUsers(session.bookId) },
                    onBack = { campfire.setShowInvite(false) },
                    onContinue = { newUserIds ->
                        campfireViewModel.updateSettings(
                            CampfireSettings(
                                name = session.name,
                                controlMode = session.controlMode,
                                inviteOnly = session.inviteOnly,
                                invitedUserIds = (session.invitedPending.map { it.userId } + newUserIds).distinct(),
                            ),
                        )
                        campfire.setShowInvite(false)
                    },
                )
            } else {
                val progress = provideProgress()
                CampfireRoomScreen(
                    session = session,
                    book = book,
                    isPlaying = activeState?.isPlaying == true,
                    progressFraction = progress.chapterProgress,
                    positionLabel = progress.chapterPosition.formatPlaybackTime(),
                    remainingLabel = "-" + (progress.chapterDuration - progress.chapterPosition).formatPlaybackTime(),
                    feed = campfire.feed,
                    floatingReactions = campfire.reactions,
                    onReactionFinished = campfire.onReactionFinished,
                    onLeave = campfireViewModel::leave,
                    onInvite = { campfire.setShowInvite(true) },
                    onPlayPause = campfire.playPause ?: {},
                    onSkipBack = {
                        campfireViewModel.seekTo(
                            (progress.bookPositionMs - SKIP_BACK_MS).coerceAtLeast(0L),
                        )
                    },
                    onSkipForward = { campfireViewModel.seekTo(progress.bookPositionMs + SKIP_FORWARD_MS) },
                    onScrub = { fraction ->
                        campfireViewModel.seekTo(
                            progress.bookPositionMs - progress.chapterPositionMs +
                                (progress.chapterDurationMs * fraction).toLong(),
                        )
                    },
                    onSend = campfireViewModel::sendChat,
                    onQuickReact = campfireViewModel::sendReaction,
                )
            }
        }
    }
}

/**
 * Campfire wiring for [NowPlayingHost], extracted to keep the host inside the cognitive-complexity
 * budget. Carries the derived session (only when it matches the playing book), the book identity
 * for the full-screen Lobby/Room (task L3), the ambient feed, the transient reaction UI state, and
 * the campfire-routed transport intents — `null` when no session is active, so callers fall back to
 * the plain player controls.
 */
private class CampfireHostUi(
    val state: CampfireScreenUiState,
    val session: CampfireScreenUiState.Active?,
    val book: CampfireFlowBook?,
    val feed: List<CampfireFeedRow>,
    val reactions: List<FloatingReaction>,
    val onReactionFinished: (Long) -> Unit,
    val showInvite: Boolean,
    val setShowInvite: (Boolean) -> Unit,
    val dismissedRejoinStateVersion: Long?,
    val setDismissedRejoinStateVersion: (Long?) -> Unit,
    val playPause: (() -> Unit)?,
)

/**
 * Collects [CampfireViewModel] state/events into a [CampfireHostUi] for [NowPlayingHost]: derives
 * the active session for the playing book, folds one-shot events (ControlDenied → snackbar,
 * ReactionReceived → floating overlay entry), and builds the campfire-routed play/pause intent.
 * Only play/pause funnels through the room — it is the one control [NowPlayingHost] can still
 * offer from the mini bar; the full transport (skip/scrub) lives entirely inside `CampfireRoomScreen`.
 */
@Composable
private fun rememberCampfireHost(
    campfireViewModel: CampfireViewModel,
    playingState: NowPlayingState.Active?,
    snackbarHostState: SnackbarHostState?,
    userRepository: UserRepository = koinInject(),
    bookRepository: BookRepository = koinInject(),
): CampfireHostUi {
    val campfireState by campfireViewModel.state.collectAsStateWithLifecycle()
    // The lobby is pre-playback — nothing is loaded until the host lights the fire — so an Active
    // session in LOBBY must show regardless of what (if anything) is playing. Once LIVE, the campfire
    // controller has loaded the campfire's book, so the now-playing-book match holds for the room and
    // keeps the campfire from hijacking the host of an unrelated solo book.
    val session =
        (campfireState as? CampfireScreenUiState.Active)
            ?.takeIf { it.phase == CampfirePhase.LOBBY || it.bookId == playingState?.bookId }

    var showInvite by remember { mutableStateOf(false) }
    LaunchedEffect(session == null) {
        if (session == null) showInvite = false
    }

    val reactions = remember { mutableStateListOf<FloatingReaction>() }
    var dismissedRejoinStateVersion by remember { mutableStateOf<Long?>(null) }

    val currentUser by userRepository.observeCurrentUser().collectAsStateWithLifecycle(initialValue = null)
    val feed = session?.let { rememberCampfireFeed(it, currentUser?.idString) } ?: emptyList()

    // The lobby has no playback yet, so the book cannot come from playingState — resolve the
    // campfire's book from its id so the flow (Lobby/Invite/Room) has a title and cover to render.
    // Once the fire is lit and the campfire's book is playing, the playingState branch below takes over.
    val sessionBookId = session?.bookId
    val sessionBook by remember(sessionBookId) {
        if (sessionBookId != null) bookRepository.observeBookListItems(listOf(sessionBookId)) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val book =
        playingState?.let {
            CampfireFlowBook(
                bookId = it.bookId,
                title = it.title,
                subtitle = it.narrators.joinToString(", ") { narrator -> narrator.name }.ifBlank { it.author },
                coverPath = it.coverPath,
                coverHash = it.coverHash,
                coverBlurHash = it.coverBlurHash,
            )
        } ?: sessionBook.firstOrNull()?.let { item ->
            CampfireFlowBook(
                bookId = item.id.value,
                title = item.title,
                subtitle =
                    item.narrators
                        .joinToString(", ") { n -> n.name }
                        .ifBlank { item.authors.joinToString(", ") { a -> a.name } },
                coverPath = item.coverPath,
                coverHash = item.coverHash,
                coverBlurHash = item.coverBlurHash,
            )
        }

    LaunchedEffect(campfireViewModel) {
        campfireViewModel.events.collect { event ->
            when (event) {
                // NotStarted (playback attempted before the host lit the fire) reuses the same
                // denial snackbar as ControlDenied — playback commands are unreachable from the
                // Lobby screen anyway (no transport controls render there).
                CampfireScreenEvent.ControlDenied, CampfireScreenEvent.NotStarted -> {
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
        book = book,
        feed = feed,
        reactions = reactions,
        onReactionFinished = { id -> reactions.removeAll { it.id == id } },
        showInvite = showInvite,
        setShowInvite = { showInvite = it },
        dismissedRejoinStateVersion = dismissedRejoinStateVersion,
        setDismissedRejoinStateVersion = { dismissedRejoinStateVersion = it },
        playPause =
            session?.let {
                { if (isPlaying) campfireViewModel.pause() else campfireViewModel.play() }
            },
    )
}

/**
 * Campfire session overlays — spoiler confirm, rejoin confirm. Independent of [NowPlayingOverlay]
 * (the plain player's own overlay enum) since a spoiler/rejoin prompt can surface before the
 * full-screen player is ever expanded. The chat sheet is gone (task L3 absorbed it into
 * `CampfireRoomScreen`'s always-on ambient overlay).
 */
@Composable
private fun CampfireOverlays(
    campfire: CampfireHostUi,
    campfireViewModel: CampfireViewModel,
) {
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
