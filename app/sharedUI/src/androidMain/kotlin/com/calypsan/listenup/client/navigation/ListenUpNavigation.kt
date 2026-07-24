package com.calypsan.listenup.client.navigation

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_retry
import listenup.composeapp.generated.resources.error_book_not_connected
import listenup.composeapp.generated.resources.error_book_wrong_server
import listenup.composeapp.generated.resources.startup_setup_check_failed_message
import listenup.composeapp.generated.resources.startup_setup_check_failed_title
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.data.repository.DeepLinkManager
import com.calypsan.listenup.client.share.ShareResolution
import com.calypsan.listenup.client.share.ShareTarget
import com.calypsan.listenup.client.share.ShareTargetResolver
import com.calypsan.listenup.client.data.repository.ShortcutAction
import com.calypsan.listenup.client.data.repository.ShortcutActionManager
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.usecase.auth.LogoutUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.design.components.ProvideNowPlayingInsets
import com.calypsan.listenup.client.design.components.latchFootprint
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.features.connect.ServerSelectScreen
import com.calypsan.listenup.client.features.connect.ServerSetupScreen
import com.calypsan.listenup.client.features.invite.JoinScreen
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.features.nowplaying.NowPlayingHost
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingViewModel
import com.calypsan.listenup.client.features.nowplaying.DockedNowPlayingBarHeight
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.features.shell.components.ConnectionHealthBanner
import com.calypsan.listenup.client.features.shell.shellDestinationSaver
import com.calypsan.listenup.client.presentation.auth.PendingApprovalViewModel
import com.calypsan.listenup.client.presentation.connection.ConnectionHealthViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.calypsan.listenup.client.presentation.startup.AppStartupViewModel
import com.calypsan.listenup.client.presentation.startup.LibraryReadiness
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.navigation.entries.adminEntries
import com.calypsan.listenup.client.navigation.entries.bookEntries
import com.calypsan.listenup.client.navigation.entries.contributorEntries
import com.calypsan.listenup.client.navigation.entries.librarySetupEntry
import com.calypsan.listenup.client.navigation.entries.profileEntries
import com.calypsan.listenup.client.navigation.entries.seriesEntries
import com.calypsan.listenup.client.navigation.entries.settingsEntries
import com.calypsan.listenup.client.navigation.entries.shelfEntries
import com.calypsan.listenup.client.navigation.entries.shellEntry

private val logger = KotlinLogging.logger {}

/**
 * Initial mini-player clearance used before the bar reports its first measurement. Tracks
 * [DockedNowPlayingBarHeight] so the desktop/tablet docked bar (which doesn't self-report its
 * footprint yet) and the phone floating bar share the same seed value. Replaced by the real
 * measured footprint once [NowPlayingHost] reports it.
 */
private val DefaultNowPlayingFootprint = DockedNowPlayingBarHeight

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
            ServerSetupNavigation()
        }

        AuthState.CheckingServer -> {
            LoadingScreen("Checking server...")
        }

        AuthState.NeedsSetup -> {
            SetupNavigation()
        }

        is AuthState.NeedsLogin -> {
            LoginNavigation(authSession, currentAuthState.openRegistration)
        }

        is AuthState.PendingApproval -> {
            PendingApprovalNavigation(
                userId = currentAuthState.userId.value,
                email = currentAuthState.email,
            )
        }

        is AuthState.Authenticated, is AuthState.SessionLapsed -> {
            // SessionLapsed renders the SAME shell (M2/M3): library, downloads, playback of
            // downloaded content all work. Sharing one branch keeps the composition call site —
            // and therefore the back stack — stable across the lapse/re-auth transition.
            AuthenticatedNavigation(authSession)
        }
    }
}

/**
 * Navigation for pending approval screen.
 *
 * Shows the pending approval screen for users who have registered
 * but are waiting for admin approval. Handles:
 * - RPC watch for real-time approval notification
 * - Auto-login on approval
 * - Cancel to return to login
 */
@Composable
private fun PendingApprovalNavigation(
    userId: String,
    email: String,
) {
    val viewModel: PendingApprovalViewModel =
        koinViewModel {
            org.koin.core.parameter
                .parametersOf(userId, email)
        }

    com.calypsan.listenup.client.features.auth.PendingApprovalScreen(
        viewModel = viewModel,
        onNavigateToLogin = {
            // Auth state automatically updates after clearPendingRegistration.
        },
    )
}

/**
 * Navigation for the invite-claim flow.
 *
 * Shows [JoinScreen] and handles completion/cancellation. The RPC invite vertical
 * reads the server URL from [ServerConfig]; the deep link carries its own URL, so
 * [JoinScreen] hands both URL and code to the ViewModel's `start`, which
 * persists the URL before the lookup runs (one coroutine, deterministic order —
 * no race on a fresh install). On a successful claim the repository persists the
 * issued session (AuthState → Authenticated); once [JoinScreen] reports the claim
 * via `onClaimed`, the invite is consumed and auth-state routing takes over.
 */
@Composable
private fun JoinNavigation(
    serverUrl: String,
    inviteCode: String,
    remoteUrl: String?,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
) {
    JoinScreen(
        onClaimed = onComplete,
        onCancel = onCancel,
        initialCode = inviteCode,
        serverUrl = serverUrl,
        remoteUrl = remoteUrl,
    )
}

/**
 * Loading screen shown during auth state initialization.
 * Displayed briefly on app start while checking for stored credentials.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
private fun LoadingScreen(message: String = "Loading...") {
    FullScreenLoadingIndicator()
}

/**
 * Full-screen error surface shown when an admin's library-setup check fails.
 *
 * Mirrors the existing error-state pattern (icon + message + retry button) so the
 * admin gets an honest, retryable failure instead of silently landing in an empty
 * Shell. [onRetry] re-runs the setup-status check.
 */
@Composable
private fun SetupCheckFailedScreen(onRetry: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(Res.string.startup_setup_check_failed_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(Res.string.startup_setup_check_failed_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            ListenUpButton(text = stringResource(Res.string.common_retry), onClick = onRetry)
        }
    }
}

/**
 * Server setup navigation - shown when no server URL is configured.
 *
 * Flow:
 * 1. ServerSelectScreen - shows discovered servers + manual option
 * 2. ServerSetupScreen - manual URL entry (if user clicks "Add Manually")
 *
 * After successful selection/verification, AuthState changes trigger automatic navigation.
 */
@Composable
private fun ServerSetupNavigation() {
    val backStack = rememberNavBackStack(ServerSelect)

    NavDisplay(
        backStack = backStack,
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        onBack = {
            if (backStack.size > 1) {
                backStack.removeAt(backStack.lastIndex)
            }
        },
        entryProvider =
            entryProvider {
                entry<ServerSelect> {
                    ServerSelectScreen(
                        onServerActivated = {
                            // Server is selected, AuthState will change automatically
                        },
                        onManualEntryRequested = {
                            backStack.add(ServerSetup)
                        },
                    )
                }
                entry<ServerSetup> {
                    ServerSetupScreen(
                        onServerVerified = {
                            // URL is saved, AuthState will change automatically
                            // Pop back to select; the auth screen takes over once AuthState updates
                        },
                        onBack = {
                            backStack.removeAt(backStack.lastIndex)
                        },
                    )
                }
            },
    )
}

/**
 * Setup navigation - shown when server needs initial root user.
 * After successful setup, AuthState.Authenticated triggers automatic navigation.
 */
@Composable
private fun SetupNavigation() {
    val backStack = rememberNavBackStack(Setup)

    NavDisplay(
        backStack = backStack,
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        entryProvider =
            entryProvider {
                entry<Setup> {
                    com.calypsan.listenup.client.features.auth
                        .SetupScreen()
                }
            },
    )
}

/**
 * Login navigation - shown when server is configured but user needs to authenticate.
 * After successful login, AuthState.Authenticated triggers automatic navigation.
 *
 * @param openRegistration Whether the server allows public registration
 */
@Composable
private fun LoginNavigation(
    authSession: AuthSession,
    openRegistration: Boolean,
) {
    val scope = rememberCoroutineScope()
    val backStack = rememberNavBackStack(Login)
    val serverConfig: com.calypsan.listenup.client.domain.repository.ServerConfig = koinInject()

    // Refresh open registration value from server
    // This ensures the "Create Account" link appears if admin enabled it
    LaunchedEffect(Unit) {
        authSession.refreshOpenRegistration()
    }

    NavDisplay(
        backStack = backStack,
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        entryProvider =
            entryProvider {
                entry<Login> {
                    com.calypsan.listenup.client.features.auth
                        .LoginScreen(
                            openRegistration = openRegistration,
                            onChangeServer = {
                                scope.launch {
                                    // Clear server URL to go back to server selection
                                    serverConfig.disconnectFromServer()
                                }
                            },
                            onRegister = {
                                backStack.add(Register)
                            },
                        )
                }
                entry<Register> {
                    com.calypsan.listenup.client.features.auth
                        .RegisterScreen(
                            onBackClick = {
                                backStack.removeAt(backStack.lastIndex)
                            },
                        )
                }
            },
    )
}

/**
 * Resets navigation to the [Shell] root, clearing any detail screens on the way. No-op when the
 * shell is already the top of the back stack. Shared by shortcut routing and share-link routing so
 * "land the user on a known root before pushing a detail" has exactly one definition.
 */
private fun resetToShell(backStack: NavBackStack<NavKey>) {
    if (backStack.lastOrNull() != Shell) {
        backStack.clear()
        backStack.add(Shell)
    }
}

/**
 * Routes a launcher/shortcut [ShortcutAction] to the right playback or navigation effect.
 *
 * Extracted from [AuthenticatedNavigation] so the composable's cyclomatic complexity stays
 * within budget — this is plain control flow with no Compose surface of its own. [backStack]
 * mutations and [onSelectShellDestination] are invoked from the caller's `LaunchedEffect`
 * coroutine, preserving the original ordering and threading.
 */
private suspend fun handleShortcutAction(
    action: ShortcutAction,
    homeRepository: HomeRepository,
    nowPlayingViewModel: NowPlayingViewModel,
    backStack: NavBackStack<NavKey>,
    onSelectShellDestination: (ShellDestination) -> Unit,
) {
    when (action) {
        is ShortcutAction.Resume -> {
            // Get the most recent book and play it
            val result = homeRepository.getContinueListening(1)
            if (result is AppResult.Success && result.data.isNotEmpty()) {
                val book = result.data.first()
                logger.info { "Resuming book: ${book.title}" }
                nowPlayingViewModel.playBook(BookId(book.bookId))
                nowPlayingViewModel.expand()
            } else {
                logger.warn { "No recent book to resume" }
                // Navigate to library as fallback
                resetToShell(backStack)
                onSelectShellDestination(ShellDestination.Library)
            }
        }

        is ShortcutAction.PlayBook -> {
            logger.info { "Playing book: ${action.bookId}" }
            nowPlayingViewModel.playBook(BookId(action.bookId))
            nowPlayingViewModel.expand()
        }

        is ShortcutAction.Search -> {
            // Navigate to library (search tab)
            resetToShell(backStack)
            onSelectShellDestination(ShellDestination.Library)
        }

        is ShortcutAction.NavigateToBook -> {
            logger.info { "Navigating to book: ${'$'}{action.bookId}" }
            // Ensure we're on Shell first, then navigate to book detail
            resetToShell(backStack)
            backStack.add(BookDetail(action.bookId))
        }

        is ShortcutAction.SleepTimer -> {
            // If playing, show sleep timer; otherwise resume + set timer
            val result = homeRepository.getContinueListening(1)
            if (result is AppResult.Success && result.data.isNotEmpty()) {
                val book = result.data.first()
                nowPlayingViewModel.playBook(BookId(book.bookId))
                nowPlayingViewModel.expand()
                // Let the user interact with sleep timer in the player
            }
        }

        is ShortcutAction.NavigateToAbsImport -> {
            logger.info { "Navigating to ABS import: ${action.importId}" }
            resetToShell(backStack)
            backStack.add(AdminBackups)
            backStack.add(ImportFlow)
        }
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
@Composable
private fun AuthenticatedNavigation(
    authSession: AuthSession,
    logoutUseCase: LogoutUseCase = koinInject(),
    syncRepository: SyncRepository = koinInject(),
    shortcutActionManager: ShortcutActionManager = koinInject(),
    homeRepository: HomeRepository = koinInject(),
    serverConfig: ServerConfig = koinInject(),
    deepLinkManager: DeepLinkManager = koinInject(),
) {
    val scope = rememberCoroutineScope()

    // ViewModels for shortcut action handling
    val nowPlayingViewModel: NowPlayingViewModel = koinViewModel()

    // Mini-player clearance: the bar is the producer of its own footprint, the detail screens
    // are the consumers via LocalNowPlayingInsets. The bar is "visible" (and thus reserves space)
    // only while a book is Active and the full-screen player is collapsed. We latch the measured
    // footprint so transient zero sizes during the slide-out animation don't collapse the inset.
    val nowPlayingScreenState by nowPlayingViewModel.screenState.collectAsStateWithLifecycle()
    val barVisible =
        nowPlayingScreenState.state is NowPlayingState.Active && !nowPlayingScreenState.isExpanded
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

    // Pop the banner-pushed re-auth Login entry the moment the session is restored,
    // landing the user back on the shell exactly where they left off.
    val currentAuthState by authSession.authState.collectAsStateWithLifecycle()
    LaunchedEffect(currentAuthState) {
        if (currentAuthState is AuthState.Authenticated && backStack.lastOrNull() == Login) {
            backStack.removeAt(backStack.lastIndex)
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
    var profileRefreshKey by rememberSaveable { mutableStateOf(0) }

    // App-wide snackbar state - provided to all screens via CompositionLocal
    val snackbarHostState = remember { SnackbarHostState() }

    // Hoisted sign-out action shared by shell and settings entries. Delegates to LogoutUseCase —
    // the single sign-out choke point (engine stop, RPC invalidation, library-data + pending-op
    // clear, token clear, user clear) — so this nav-level action can't drift from what Settings'
    // own sign-out does.
    val onSignOut: () -> Unit = {
        scope.launch {
            logoutUseCase()
        }
    }

    // Resolve a pending BOOK share-link target against the connected server.
    // Invite targets are handled at the top level; this block ignores them.
    val pendingTarget by deepLinkManager.pendingTarget.collectAsStateWithLifecycle()
    val wrongServerMessage = stringResource(Res.string.error_book_wrong_server)
    val notConnectedMessage = stringResource(Res.string.error_book_not_connected)
    LaunchedEffect(pendingTarget) {
        val book = pendingTarget as? ShareTarget.Book ?: return@LaunchedEffect
        when (val resolution = ShareTargetResolver.resolve(book, serverConfig.getConnectedServerId())) {
            is ShareResolution.OpenBook -> {
                resetToShell(backStack)
                backStack.add(BookDetail(resolution.bookId.value))
            }

            is ShareResolution.WrongServer -> {
                snackbarHostState.showSnackbar(wrongServerMessage)
            }

            is ShareResolution.NotConnected -> {
                snackbarHostState.showSnackbar(notConnectedMessage)
            }

            // OpenInviteClaim handled at top level; NoAccess not produced by the pure resolver
            // (an inaccessible book opens Book Detail, which shows its own empty state).
            else -> {}
        }
        // consumeTarget after showSnackbar (which suspends until dismissed) so the link stays live
        // until the user has actually seen the error.
        deepLinkManager.consumeTarget()
    }

    ShortcutActionEffect(
        shortcutActionManager = shortcutActionManager,
        homeRepository = homeRepository,
        nowPlayingViewModel = nowPlayingViewModel,
        backStack = backStack,
        onSelectShellDestination = { currentShellDestination = it },
    )

    // Wrap navigation with NowPlayingHost for persistent mini player.
    // ProvideNowPlayingInsets publishes the mini-player clearance to every detail screen below,
    // so NavDisplay content can pad itself clear of the floating bar (the bar measures itself inside
    // AuthenticatedNavOverlays and latches its footprint back up here).
    ProvideNowPlayingInsets(barVisible = barVisible, latchedFootprint = latchedFootprint) {
        CompositionLocalProvider(
            LocalSnackbarHostState provides snackbarHostState,
            LocalDeviceContext provides koinInject<DeviceContext>(),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                NavDisplay(
                    backStack = backStack,
                    entryDecorators =
                        listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                    // Only handle back if we're not at root - let system handle back-to-home
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                        // When size == 1, don't pop - allows system back-to-home animation
                    },
                    // Global slide transitions for all navigation
                    transitionSpec = {
                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                    },
                    popTransitionSpec = {
                        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                    },
                    predictivePopTransitionSpec = {
                        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                    },
                    entryProvider =
                        authenticatedNavEntries(
                            backStack = backStack,
                            // Deferred reads: the entry content reads these inside the Shell composable so
                            // tab/readiness changes recompose it (NavDisplay won't re-invoke the builder).
                            currentShellDestination = { currentShellDestination },
                            onShellDestinationChange = { currentShellDestination = it },
                            nowPlayingViewModel = nowPlayingViewModel,
                            readiness = { readiness },
                            onSignOut = onSignOut,
                            startupViewModel = startupViewModel,
                            scope = scope,
                            syncRepository = syncRepository,
                            serverConfig = serverConfig,
                            profileRefreshKey = profileRefreshKey,
                            onProfileRefreshed = { profileRefreshKey++ },
                        ),
                )

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

/**
 * Builds the [entryProvider] block for all authenticated navigation destinations.
 *
 * Extracted from [AuthenticatedNavigation] to keep the orchestrator within complexity budget.
 * All parameters are passed verbatim; no logic lives here beyond wiring entries to their
 * extension functions.
 */
private fun authenticatedNavEntries(
    backStack: NavBackStack<NavKey>,
    currentShellDestination: () -> ShellDestination,
    onShellDestinationChange: (ShellDestination) -> Unit,
    nowPlayingViewModel: NowPlayingViewModel,
    readiness: () -> LibraryReadiness,
    onSignOut: () -> Unit,
    startupViewModel: AppStartupViewModel,
    scope: CoroutineScope,
    syncRepository: SyncRepository,
    serverConfig: ServerConfig,
    profileRefreshKey: Int,
    onProfileRefreshed: () -> Unit,
) = entryProvider {
    shellEntry(
        backStack = backStack,
        currentShellDestination = currentShellDestination,
        onDestinationChange = onShellDestinationChange,
        nowPlayingViewModel = nowPlayingViewModel,
        readiness = readiness,
        onSignOut = onSignOut,
        onContinueToPartialLibrary = startupViewModel::onContinueToPartialLibrary,
    )
    librarySetupEntry(backStack, startupViewModel, scope, syncRepository)
    bookEntries(backStack)
    seriesEntries(backStack)
    contributorEntries(backStack)
    adminEntries(backStack)
    profileEntries(
        backStack = backStack,
        profileRefreshKey = profileRefreshKey,
        onProfileRefreshed = onProfileRefreshed,
    )
    shelfEntries(backStack)
    settingsEntries(
        backStack = backStack,
        onSignOut = onSignOut,
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
        )
    }
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
    nowPlayingViewModel: NowPlayingViewModel,
    readiness: LibraryReadiness,
    onRetryLibrarySetupCheck: () -> Unit,
    onBarFootprintChanged: (Dp) -> Unit,
) {
    // Now Playing overlay - persistent across all navigation
    // Position adjusts based on whether bottom nav is visible (Shell vs detail screens)
    // Also animates up when snackbar is visible
    // Pass viewModel explicitly to ensure NowPlayingHost shares the same instance
    // as NowPlayingBar in AppShell - prevents state divergence between screens
    NowPlayingHost(
        hasBottomNav = backStack.lastOrNull() == Shell,
        snackbarHostState = snackbarHostState,
        onNavigateToBook = { bookId ->
            backStack.add(BookDetail(bookId))
        },
        onNavigateToSeries = { seriesId ->
            backStack.add(SeriesDetail(seriesId))
        },
        onNavigateToContributor = { contributorId ->
            backStack.add(ContributorDetail(contributorId))
        },
        onNavigateToDocument = { localPath ->
            backStack.add(DocumentViewer(localPath))
        },
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
                    if (backStack.lastOrNull() != Login) backStack.add(Login)
                }
            }
        }
    }
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

/**
 * Reads the pending [ShortcutAction] and routes it to the appropriate playback or navigation
 * effect via [handleShortcutAction].
 *
 * Extracted from [AuthenticatedNavigation] to keep the orchestrator within complexity budget.
 */
@Composable
private fun ShortcutActionEffect(
    shortcutActionManager: ShortcutActionManager,
    homeRepository: HomeRepository,
    nowPlayingViewModel: NowPlayingViewModel,
    backStack: NavBackStack<NavKey>,
    onSelectShellDestination: (ShellDestination) -> Unit,
) {
    val pendingAction by shortcutActionManager.pendingAction.collectAsStateWithLifecycle()
    LaunchedEffect(pendingAction) {
        val action = pendingAction ?: return@LaunchedEffect

        logger.info { "Processing shortcut action: $action" }

        handleShortcutAction(
            action = action,
            homeRepository = homeRepository,
            nowPlayingViewModel = nowPlayingViewModel,
            backStack = backStack,
            onSelectShellDestination = onSelectShellDestination,
        )

        // Consume the action after processing
        shortcutActionManager.consumeAction()
    }
}
