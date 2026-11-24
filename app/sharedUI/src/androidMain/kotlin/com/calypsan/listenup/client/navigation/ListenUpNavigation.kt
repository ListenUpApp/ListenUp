package com.calypsan.listenup.client.navigation

import com.calypsan.listenup.client.features.nowplaying.morph.LocalPlayerMorph
import com.calypsan.listenup.client.features.nowplaying.morph.rememberPlayerMorph
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.calypsan.listenup.client.features.setup.SetupCheckFailedScreen
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.data.repository.DeepLinkManager
import com.calypsan.listenup.client.design.transitions.LocalHeroTransitionScope
import com.calypsan.listenup.client.share.ShareTarget
import com.calypsan.listenup.client.data.repository.ShortcutAction
import com.calypsan.listenup.client.data.repository.ShortcutActionManager
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.usecase.auth.LogoutUseCase
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.design.components.ProvideRestrictedBookIds
import com.calypsan.listenup.client.features.bulkedit.PendingSelectionExit
import com.calypsan.listenup.client.design.components.ProvideNowPlayingInsets
import com.calypsan.listenup.client.design.components.isMiniPlayerShown
import com.calypsan.listenup.client.design.components.latchFootprint
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.isInShell
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.features.nowplaying.NowPlayingHost
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PlayerViewModel
import com.calypsan.listenup.client.features.nowplaying.MiniBarHeight
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.features.shell.components.ConnectionHealthBanner
import com.calypsan.listenup.client.features.shell.shellDestinationSaver
import com.calypsan.listenup.client.presentation.auth.ForgotPasswordViewModel
import com.calypsan.listenup.client.presentation.connection.ConnectionHealthViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.calypsan.listenup.client.presentation.startup.AppStartupViewModel
import com.calypsan.listenup.client.presentation.startup.LibraryReadiness
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.design.util.LocalUnsavedWorkHolds
import com.calypsan.listenup.client.design.util.UnsavedWorkHolds
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.navigation.entries.adminEntries
import com.calypsan.listenup.client.navigation.entries.bookEntries
import com.calypsan.listenup.client.navigation.entries.contributorEntries
import com.calypsan.listenup.client.navigation.entries.librarySetupEntry
import com.calypsan.listenup.client.navigation.entries.matchEntries
import com.calypsan.listenup.client.navigation.entries.notificationEntries
import com.calypsan.listenup.client.navigation.entries.profileEntries
import com.calypsan.listenup.client.navigation.entries.seriesEntries
import com.calypsan.listenup.client.navigation.entries.settingsEntries
import com.calypsan.listenup.client.navigation.entries.shelfEntries
import com.calypsan.listenup.client.navigation.entries.discoverEntries
import com.calypsan.listenup.client.navigation.entries.shellEntry

/**
 * Initial mini-player clearance before the bar reports its first measurement; every bar now reports its own (the
 * phone bar and the tablet toolbar).
 */
private val DefaultNowPlayingFootprint = MiniBarHeight

/**
 * Root navigation composable for ListenUp Android app.
 *
 * Navigation priority:
 * 1. Pending invite deep link (shows invite registration)
 * 2. Auth state-driven routing (server setup → login → library)
 *
 * Navigation automatically adjusts when auth state changes.
 * Uses Navigation 3 stable for Android (will migrate to KMP when Desktop support needed).
 */
@Composable
fun ListenUpNavigation(
    authSession: AuthSession = koinInject(),
    deepLinkManager: DeepLinkManager = koinInject(),
) {
    // Initialize auth state on first composition
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        scope.launch {
            authSession.initializeAuthState()
        }
    }

    // Observe the pending share-link target (invite branch handled here, pre-auth, as before).
    val pendingTarget by deepLinkManager.pendingTarget.collectAsStateWithLifecycle()

    // Observe auth state changes
    val authState by authSession.authState.collectAsStateWithLifecycle()

    // Check for pending invite BEFORE auth state routing
    // This allows invite claiming even when already authenticated
    (pendingTarget as? ShareTarget.Invite)?.let { invite ->
        JoinNavigation(
            serverUrl = invite.serverUrl,
            inviteCode = invite.code,
            remoteUrl = invite.remoteUrl,
            onComplete = { deepLinkManager.consumeTarget() },
            onCancel = { deepLinkManager.consumeTarget() },
        )
        return
    }

    // Route to appropriate screen based on auth state
    // Capture to local val to enable smart casting (delegated properties can't be smart cast)
    val currentAuthState = authState

    // The shell states are defined once, by AuthState.isInShell — SessionLapsed renders the SAME
    // shell as Authenticated (M2/M3): library, downloads, playback of downloaded content all work.
    // Sharing one call site keeps the composition — and therefore the back stack — stable across
    // the lapse/re-auth transition.
    if (currentAuthState.isInShell) {
        AuthenticatedNavigation(authSession)
        return
    }

    // Pre-shell states, routed exhaustively so a new AuthState variant cannot land here unnoticed.
    when (currentAuthState) {
        AuthState.Initializing -> {
            // Show blank screen while determining auth state
            // Prevents flash of wrong screen on startup
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
            )
        }

        AuthState.NeedsServerUrl -> {
            AndroidServerSetupNavigation()
        }

        AuthState.CheckingServer -> {
            LoadingScreen()
        }

        AuthState.NeedsSetup -> {
            AndroidSetupNavigation()
        }

        is AuthState.NeedsLogin -> {
            AndroidLoginNavigation(authSession, currentAuthState.openRegistration)
        }

        is AuthState.PendingApproval -> {
            AndroidPendingApprovalNavigation(
                userId = currentAuthState.userId.value,
                email = currentAuthState.email,
            )
        }

        // Unreachable: isInShell routed these into the shell above and returned. The branches stay
        // for exhaustiveness, so adding an AuthState variant still has to be routed here explicitly.
        is AuthState.Authenticated, is AuthState.SessionLapsed -> {}
    }
}

/**
 * Navigation graph for authenticated users.
 *
 * Entry point: AppShell (contains bottom nav with Home, Library, Discover)
 *
 * Predictive back behavior:
 * - Root screen (Shell): onBack doesn't pop, allowing system back-to-home animation
 * - Detail screens: Slide animations for in-app navigation
 *
 * NowPlayingHost is overlaid on top of all navigation, providing:
 * - Floating mini player above bottom nav
 * - Full screen player that expands from mini player
 *
 * When user logs out, SettingsRepository clears auth tokens,
 * triggering automatic switch to UnauthenticatedNavigation.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AuthenticatedNavigation(
    authSession: AuthSession,
    logoutUseCase: LogoutUseCase = koinInject(),
    syncRepository: SyncRepository = koinInject(),
    shortcutActionManager: ShortcutActionManager = koinInject(),
    homeRepository: HomeRepository = koinInject(),
    serverConfig: ServerConfig = koinInject(),
) {
    val scope = rememberCoroutineScope()

    // One search-request channel for the `/` key and the launcher's Search shortcut alike.
    val searchRequests = remember { ShellSearchRequests() }

    // Screens holding an upload in flight or an unsaved edit raise a hold here; an outside
    // navigation (push tap, shared link, shortcut) lands on top of them instead of clearing the stack.
    val unsavedWork = remember { UnsavedWorkHolds() }

    // ViewModels for shortcut action handling
    val nowPlayingViewModel: PlayerViewModel = koinViewModel()

    // Mini-player clearance: the bar is the producer of its own footprint, the detail screens
    // are the consumers via LocalNowPlayingInsets. The bar is "visible" (and thus reserves space)
    // only while a book is loaded (an error keeps it, gap 6) and the full-screen player is collapsed. We
    // latch the measured footprint so transient zero sizes during the slide-out animation don't collapse the inset.
    val playerState by nowPlayingViewModel.uiState.collectAsStateWithLifecycle()
    val playerExpanded by nowPlayingViewModel.expanded.collectAsStateWithLifecycle()
    var latchedFootprint by remember { mutableStateOf(DefaultNowPlayingFootprint) }

    // AppStartupViewModel holds the library-setup check result across Activity re-creations.
    // This prevents the loading screen from reappearing on every config change or short
    // foreground resume. The ViewModel lives in the Activity's ViewModelStore, so it is only
    // discarded on process death (true cold start).
    val startupViewModel: AppStartupViewModel = koinViewModel()
    // One authoritative readiness state drives every onboarding gate below — no more juggling
    // isChecking/needsLibrarySetup/checkResolved/setupCheckFailed/isServerScanning across three sites.
    val readiness by startupViewModel.readiness.collectAsStateWithLifecycle()

    // Hoist navigation state above the isChecking check so it survives loading periods.
    // rememberNavBackStack persists across configuration changes and process death
    // (rubric §Navigation rule); the back stack survives when isChecking toggles,
    // preventing navigation position from being lost on app resume.
    val backStack = rememberNavBackStack(Shell)
    // The PDF reader carries its own player, so the mini-player gives way to it there.
    val isReaderShowing = backStack.lastOrNull() is DocumentViewer
    val barVisible =
        isMiniPlayerShown(
            isBookLoaded = playerState is PlayerUiState.Loaded,
            isNowPlayingExpanded = playerExpanded,
            isReaderShowing = isReaderShowing,
        )

    // Pop the banner-pushed re-auth Login entry the moment the session is restored,
    // landing the user back on the shell exactly where they left off.
    val currentAuthState by authSession.authState.collectAsStateWithLifecycle()
    LaunchedEffect(currentAuthState) {
        if (currentAuthState is AuthState.Authenticated && backStack.lastOrNull() == Login) {
            backStack.popFrom(Login)
        }
    }

    // Track shell tab state here so it survives navigation to detail screens
    var currentShellDestination by rememberSaveable(stateSaver = shellDestinationSaver) {
        mutableStateOf<ShellDestination>(ShellDestination.Home)
    }

    // Navigate to LibrarySetup the moment readiness resolves to "needs setup".
    LaunchedEffect(readiness) {
        if (readiness is LibraryReadiness.NeedsSetup && backStack.lastOrNull() != LibrarySetup) {
            backStack.clear()
            backStack.add(LibrarySetup)
        }
    }

    // Track profile refresh - incremented when profile is updated to trigger refresh
    var profileRefreshKey by rememberSaveable { mutableIntStateOf(0) }

    // App-wide snackbar state - provided to all screens via CompositionLocal
    val snackbarHostState = remember { SnackbarHostState() }

    // Outlives every entry, deliberately: the screen that owns the selection and the editor that
    // consumes it are never composed at the same time.
    val pendingSelectionExit = remember { PendingSelectionExit() }

    // Hoisted sign-out action shared by shell and settings entries. Delegates to LogoutUseCase —
    // the single sign-out choke point (engine stop, RPC invalidation, library-data + pending-op
    // clear, token clear, user clear) — so this nav-level action can't drift from what Settings'
    // own sign-out does.
    val onSignOut: () -> Unit = { scope.launch { logoutUseCase() } }

    ShareLinkEffect(
        serverConfig = serverConfig,
        backStack = backStack,
        snackbarHostState = snackbarHostState,
        holdsUnsavedWork = { unsavedWork.isHeld },
        collapseNowPlaying = nowPlayingViewModel::collapse,
    )

    ShortcutActionEffect(
        shortcutActionManager = shortcutActionManager,
        homeRepository = homeRepository,
        nowPlayingViewModel = nowPlayingViewModel,
        backStack = backStack,
        onSelectShellDestination = { currentShellDestination = it },
        onOpenSearch = searchRequests::request,
        holdsUnsavedWork = { unsavedWork.isHeld },
    )

    // The lock on every book card: one admin-gated set for the whole app (empty for members).
    ProvideRestrictedBookIds {
        // Wrap navigation with NowPlayingHost for persistent mini player.
        // ProvideNowPlayingInsets publishes the mini-player clearance to every detail screen below,
        // so NavDisplay content can pad itself clear of the floating bar (the bar measures itself inside
        // AuthenticatedNavOverlays and latches its footprint back up here).
        // One morph progress for the player host and the shell's bar and navigation bar, which live on either side of
        // NavDisplay; the host animates it toward the shared destination.
        val playerMorph = rememberPlayerMorph(nowPlayingViewModel)
        ProvideNowPlayingInsets(barVisible = barVisible, latchedFootprint = latchedFootprint) {
            CompositionLocalProvider(
                LocalPlayerMorph provides playerMorph,
                LocalSnackbarHostState provides snackbarHostState,
                LocalUnsavedWorkHolds provides unsavedWork,
                LocalDeviceContext provides koinInject<DeviceContext>(),
            ) {
                AppKeyboardShortcuts(nowPlayingViewModel, backStack, searchRequests) {
                    // Hero transitions: the layout must enclose BOTH halves of every shared pair, so it
                    // wraps NavDisplay only. AuthenticatedNavOverlays stays outside deliberately — the
                    // now-playing bar is not an entry, and a cover flying past it should pass under it.
                    val transitions = rememberScreenTransitions()
                    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                        CompositionLocalProvider(LocalHeroTransitionScope provides this) {
                            NavDisplay(
                                backStack = backStack,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                entryDecorators =
                                    listOf(
                                        rememberSaveableStateHolderNavEntryDecorator(),
                                        rememberViewModelStoreNavEntryDecorator(),
                                    ),
                                // From the two-pane width a book opened from a series or contributor sits
                                // beside it; below that (and for every other stack) the single pane.
                                sceneStrategies = listOf(rememberListDetailSceneStrategy()),
                                // Only handle back if we're not at root - at size 1 the system's back-to-home
                                // animation takes over.
                                onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                                transitionSpec = { transitions.push() },
                                popTransitionSpec = { transitions.pop() },
                                predictivePopTransitionSpec = { edge -> transitions.predictivePop(edge) },
                                entryProvider =
                                    authenticatedNavEntries(
                                        backStack = backStack,
                                        shell =
                                            ShellWiring(
                                                currentDestination = { currentShellDestination },
                                                onDestinationChange = { currentShellDestination = it },
                                                readiness = { readiness },
                                                onSignOut = onSignOut,
                                                onOpenSearch = searchRequests::request,
                                                holdsUnsavedWork = { unsavedWork.isHeld },
                                            ),
                                        nowPlayingViewModel = nowPlayingViewModel,
                                        startupViewModel = startupViewModel,
                                        scope = scope,
                                        syncRepository = syncRepository,
                                        serverConfig = serverConfig,
                                        profileRefreshKey = profileRefreshKey,
                                        onProfileRefreshed = { profileRefreshKey++ },
                                        homeRepository = homeRepository,
                                        snackbarHostState = snackbarHostState,
                                        pendingSelectionExit = pendingSelectionExit,
                                    ),
                            )
                        }
                    }

                    AuthenticatedNavOverlays(
                        backStack = backStack,
                        snackbarHostState = snackbarHostState,
                        nowPlayingViewModel = nowPlayingViewModel,
                        readiness = readiness,
                        onRetryLibrarySetupCheck = { startupViewModel.retryLibrarySetupCheck() },
                        onBarFootprintChanged = { measured ->
                            latchedFootprint = latchFootprint(latchedFootprint, measured, barVisible)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The shell's live wiring, read lazily so tab and readiness changes recompose the Shell entry
 * (NavDisplay won't re-invoke the entry builder).
 */
private data class ShellWiring(
    val currentDestination: () -> ShellDestination,
    val onDestinationChange: (ShellDestination) -> Unit,
    val readiness: () -> LibraryReadiness,
    val onSignOut: () -> Unit,
    val onOpenSearch: () -> Unit,
    val holdsUnsavedWork: () -> Boolean,
)

/**
 * Builds the [entryProvider] block for all authenticated navigation destinations.
 *
 * Extracted from [AuthenticatedNavigation] to keep the orchestrator within complexity budget.
 * All parameters are passed verbatim; no logic lives here beyond wiring entries to their
 * extension functions.
 */
private fun authenticatedNavEntries(
    backStack: NavBackStack<NavKey>,
    shell: ShellWiring,
    nowPlayingViewModel: PlayerViewModel,
    startupViewModel: AppStartupViewModel,
    scope: CoroutineScope,
    syncRepository: SyncRepository,
    serverConfig: ServerConfig,
    profileRefreshKey: Int,
    onProfileRefreshed: () -> Unit,
    homeRepository: HomeRepository,
    snackbarHostState: SnackbarHostState,
    pendingSelectionExit: PendingSelectionExit,
) = entryProvider {
    shellEntry(
        backStack = backStack,
        currentShellDestination = shell.currentDestination,
        onDestinationChange = shell.onDestinationChange,
        nowPlayingViewModel = nowPlayingViewModel,
        readiness = shell.readiness,
        onSignOut = shell.onSignOut,
        onContinueToPartialLibrary = startupViewModel::onContinueToPartialLibrary,
        pendingSelectionExit = pendingSelectionExit,
    )
    librarySetupEntry(
        backStack = backStack,
        startupViewModel = startupViewModel,
        scope = scope,
        syncRepository = syncRepository,
    )
    destinationEntries(
        backStack = backStack,
        scope = scope,
        snackbarHostState = snackbarHostState,
        pendingSelectionExit = pendingSelectionExit,
        profileRefreshKey = profileRefreshKey,
        onProfileRefreshed = onProfileRefreshed,
        onNotificationAction =
            shortcutActionDispatcher(
                scope = scope,
                homeRepository = homeRepository,
                nowPlayingViewModel = nowPlayingViewModel,
                backStack = backStack,
                onSelectShellDestination = shell.onDestinationChange,
                onOpenSearch = shell.onOpenSearch,
                holdsUnsavedWork = shell.holdsUnsavedWork,
            ),
    )
    // Re-auth entry pushed by the shell banner's "Sign in" action while SessionLapsed.
    // Back returns to the shell — sign-in is never forced (M3). On success, AuthState flips
    // to Authenticated and AuthenticatedNavigation pops this entry automatically.
    entry<Login> {
        com.calypsan.listenup.client.features.auth.LoginScreen(
            openRegistration = false,
            onChangeServer = {
                scope.launch { serverConfig.disconnectFromServer() }
            },
            onForgotPassword = {
                backStack.add(ForgotPassword)
            },
            // Re-signing-in to a lapsed session: claiming an invite would mean a different account.
            onClaimInvite = null,
        )
    }
    // Reachable only from the re-auth Login above. A lapsed session whose password is lost
    // needs the same admin-approval flow — completion revokes every session server-side, so
    // the user lands back here and signs in with the new password.
    entry<ForgotPassword> {
        val viewModel: ForgotPasswordViewModel = koinViewModel()
        com.calypsan.listenup.client.features.auth.ForgotPasswordScreen(
            viewModel = viewModel,
            onBack = {
                backStack.popFrom(ForgotPassword)
            },
        )
    }
}

/**
 * Every screen pushed above the shell — each [Route] a tap, a link or a notification can open.
 *
 * Split from [authenticatedNavEntries] so the set is reachable without the shell's live
 * ViewModels: `DestinationEntriesTest` asks it for every [Route] and fails on any that would reach
 * the provider's "Unknown screen" fallback, which is a crash on the device the moment it is opened.
 */
internal fun EntryProviderScope<NavKey>.destinationEntries(
    backStack: NavBackStack<NavKey>,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    pendingSelectionExit: PendingSelectionExit,
    profileRefreshKey: Int,
    onProfileRefreshed: () -> Unit,
    onNotificationAction: (ShortcutAction) -> Unit,
) {
    bookEntries(
        backStack = backStack,
        scope = scope,
        snackbarHostState = snackbarHostState,
        pendingSelectionExit = pendingSelectionExit,
    )
    seriesEntries(backStack)
    contributorEntries(backStack)
    matchEntries(backStack)
    adminEntries(backStack)
    profileEntries(
        backStack = backStack,
        profileRefreshKey = profileRefreshKey,
        onProfileRefreshed = onProfileRefreshed,
    )
    shelfEntries(backStack)
    discoverEntries(backStack)
    notificationEntries(backStack = backStack, onAction = onNotificationAction)
    settingsEntries(
        backStack = backStack,
        scope = scope,
        snackbarHostState = snackbarHostState,
    )
}

/**
 * Overlays rendered inside the authenticated [Box]: the NowPlaying host, the app-wide snackbar,
 * and the single readiness gate that covers non-shell startup states.
 *
 * Extracted from [AuthenticatedNavigation] to keep the orchestrator within complexity budget.
 */
@Composable
private fun BoxScope.AuthenticatedNavOverlays(
    backStack: NavBackStack<NavKey>,
    snackbarHostState: SnackbarHostState,
    nowPlayingViewModel: PlayerViewModel,
    readiness: LibraryReadiness,
    onRetryLibrarySetupCheck: () -> Unit,
    onBarFootprintChanged: (Dp) -> Unit,
) {
    // A reader opened from Now Playing returns to it when it is left, as iOS's reader over Now Playing does.
    val top = backStack.lastOrNull()
    var previousStack by remember { mutableStateOf(backStack.toList()) }
    LaunchedEffect(top) {
        val currentStack = backStack.toList()
        if (reexpandsNowPlaying(previous = previousStack, current = currentStack)) nowPlayingViewModel.expand()
        previousStack = currentStack
    }

    // Now Playing overlay - persistent across all navigation
    // Position adjusts based on whether bottom nav is visible (Shell vs detail screens)
    // Also animates up when snackbar is visible
    // Pass viewModel explicitly to ensure NowPlayingHost shares the same instance
    // as NowPlayingBar in AppShell - prevents state divergence between screens
    NowPlayingHost(
        isShellShowing = backStack.lastOrNull() == Shell,
        isReaderShowing = backStack.lastOrNull() is DocumentViewer,
        snackbarHostState = snackbarHostState,
        onNavigateToSeries = { seriesId ->
            backStack.add(SeriesDetail(seriesId))
        },
        onNavigateToContributor = { contributorId ->
            backStack.add(ContributorDetail(contributorId))
        },
        navigation = remember(backStack) { backStack.playerNavigation() },
        viewModel = nowPlayingViewModel,
        onBarFootprintChanged = onBarFootprintChanged,
    )

    // App-wide snackbar - positioned at bottom, mini player animates up when visible
    SnackbarHost(
        hostState = snackbarHostState,
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
    )

    // Shell-level connection-health banner: session lapse, contract skew.
    val connectionHealthViewModel: ConnectionHealthViewModel = koinViewModel()
    val connectionHealth by connectionHealthViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        connectionHealthViewModel.events.collect { event ->
            when (event) {
                ConnectionHealthViewModel.Event.NavigateToSignIn -> {
                    if (backStack.lastOrNull() != Login) {
                        nowPlayingViewModel.collapse()
                        backStack.add(Login)
                    }
                }
            }
        }
    }
    if (showsConnectionBannerOver(backStack.lastOrNull())) {
        ConnectionHealthBanner(
            state = connectionHealth,
            onSignIn = connectionHealthViewModel::signIn,
            onDismiss = connectionHealthViewModel::dismiss,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    // Single readiness gate for the non-shell startup states. Populating is handled inside the
    // Shell entry above (so the shell never mounts during import); everything else is covered
    // here by one `when`. The overlays use an OPAQUE surface because they sit on top of the
    // default `Shell` back-stack entry, and FullScreenLoadingIndicator is transparent by itself
    // — without a background the shell flashes through (the "shell flash before the picker").
    when (readiness) {
        LibraryReadiness.Checking -> {
            FullScreenLoadingIndicator(modifier = Modifier.background(MaterialTheme.colorScheme.surface))
        }

        LibraryReadiness.NeedsSetup -> {
            // Bridge the gap until the LaunchedEffect above swaps the back stack to LibrarySetup;
            // once it has, the wizard renders and no overlay is needed.
            if (backStack.lastOrNull() != LibrarySetup) {
                FullScreenLoadingIndicator(modifier = Modifier.background(MaterialTheme.colorScheme.surface))
            }
        }

        LibraryReadiness.CheckFailed -> {
            // Honest over silent: surface a retryable error instead of an empty Shell.
            SetupCheckFailedScreen(onRetry = onRetryLibrarySetupCheck)
        }

        // Populating renders inside the Shell entry; Ready shows the shell — no overlay for either.
        is LibraryReadiness.Populating, LibraryReadiness.Ready -> {}
    }
}
