package com.calypsan.listenup.client.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
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
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.features.admin.AdminFocus
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.data.repository.DeepLinkManager
import com.calypsan.listenup.client.design.transitions.LocalHeroTransitionScope
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
import com.calypsan.listenup.client.design.components.ProvideRestrictedBookIds
import com.calypsan.listenup.client.features.bulkedit.PendingSelectionExit
import com.calypsan.listenup.client.design.components.ProvideNowPlayingInsets
import com.calypsan.listenup.client.design.components.isMiniPlayerShown
import com.calypsan.listenup.client.design.components.latchFootprint
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.isInShell
import com.calypsan.listenup.client.features.connect.ServerSelectScreen
import com.calypsan.listenup.client.features.connect.ServerSetupScreen
import com.calypsan.listenup.client.features.invite.JoinScreen
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.features.nowplaying.NowPlayingHost
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PlayerViewModel
import com.calypsan.listenup.client.features.nowplaying.MiniBarHeight
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.features.shell.components.ConnectionHealthBanner
import com.calypsan.listenup.client.features.shell.shellDestinationSaver
import com.calypsan.listenup.client.presentation.auth.ForgotPasswordViewModel
import com.calypsan.listenup.client.presentation.auth.PendingApprovalViewModel
import com.calypsan.listenup.client.presentation.connection.ConnectionHealthViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import com.calypsan.listenup.client.playback.SleepTimerMode
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.calypsan.listenup.client.presentation.startup.AppStartupViewModel
import com.calypsan.listenup.client.presentation.startup.LibraryReadiness
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.design.util.LocalUnsavedWorkHolds
import com.calypsan.listenup.client.design.util.UnsavedWorkHolds
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.api.result.AppResult
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

private val logger = KotlinLogging.logger {}

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
            ServerSetupNavigation()
        }

        AuthState.CheckingServer -> {
            LoadingScreen()
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

        // Unreachable: isInShell routed these into the shell above and returned. The branches stay
        // for exhaustiveness, so adding an AuthState variant still has to be routed here explicitly.
        is AuthState.Authenticated, is AuthState.SessionLapsed -> {}
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
private fun LoadingScreen() {
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
                            backStack.popFrom(ServerSetup)
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
    val instanceRepository: com.calypsan.listenup.client.domain.repository.InstanceRepository = koinInject()
    var rootResetArmed by remember { mutableStateOf(false) }

    // Refresh open registration value from server
    // This ensures the "Create Account" link appears if admin enabled it
    LaunchedEffect(Unit) {
        authSession.refreshOpenRegistration()
        // Live probe, never cached: the root-reset hatch is a 15-minute window armed by a server
        // restart, so only a fresh answer is meaningful. On failure the entry simply stays hidden.
        rootResetArmed =
            (instanceRepository.getServerInfo(forceRefresh = true) as? AppResult.Success)
                ?.run { data.rootResetArmed } == true
    }

    NavDisplay(
        backStack = backStack,
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        // Guarded like AuthNavigation's: popping the last entry would empty the stack and leave
        // NavDisplay in an undefined state (a frozen composition over cleared ViewModel stores).
        onBack = {
            if (backStack.size > 1) {
                backStack.removeAt(backStack.lastIndex)
            }
        },
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
                            onForgotPassword = {
                                backStack.add(ForgotPassword)
                            },
                            showRootResetEntry = rootResetArmed,
                        )
                }
                entry<Register> {
                    com.calypsan.listenup.client.features.auth
                        .RegisterScreen(
                            onBackClick = {
                                backStack.popFrom(Register)
                            },
                        )
                }
                entry<ForgotPassword> {
                    val viewModel: ForgotPasswordViewModel = koinViewModel()
                    // Complete and Denied both render "Back to Sign In" through onBack — that is
                    // what returns the user to Login instead of dead-ending on a terminal message.
                    com.calypsan.listenup.client.features.auth
                        .ForgotPasswordScreen(
                            viewModel = viewModel,
                            onBack = {
                                backStack.popFrom(ForgotPassword)
                            },
                        )
                }
            },
    )
}

/**
 * Whether the shell-level connection banner is drawn over [top]. Not over Login: its "Sign in"
 * would point at the page already open, and the banner, pinned above the navigation stack, reads
 * as floating loose while Login slides in beneath it. (iOS presents sign-in as a sheet over its
 * banner; web signs in inline — neither shows the banner over a login page.)
 */
internal fun showsConnectionBannerOver(top: NavKey?): Boolean = top != Login

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
 * Shows the Library tab for a shortcut that needs the shell on top, returning whether it did.
 *
 * While a screen holds unsaved work (an upload in flight, an edit not yet saved) the stack is left
 * alone: resetting it would discard that work, and the shortcut's effect can wait until the admin
 * is done.
 */
private fun showLibraryUnlessWorkHeld(
    backStack: NavBackStack<NavKey>,
    onSelectShellDestination: (ShellDestination) -> Unit,
    holdsUnsavedWork: Boolean,
): Boolean {
    if (holdsUnsavedWork) {
        logger.info { "Shortcut left the open screen alone: it holds unsaved work" }
        return false
    }
    resetToShell(backStack)
    onSelectShellDestination(ShellDestination.Library)
    return true
}

/**
 * Routes a launcher/shortcut [ShortcutAction] to the right playback or navigation effect.
 *
 * Extracted from [AuthenticatedNavigation] so the composable's cyclomatic complexity stays
 * within budget — this is plain control flow with no Compose surface of its own. [backStack]
 * mutations and [onSelectShellDestination] are invoked from the caller's `LaunchedEffect`
 * coroutine, preserving the original ordering and threading.
 */
internal suspend fun handleShortcutAction(
    action: ShortcutAction,
    homeRepository: HomeRepository,
    playback: ShortcutPlayback,
    backStack: NavBackStack<NavKey>,
    onSelectShellDestination: (ShellDestination) -> Unit,
    onOpenSearch: () -> Unit,
    holdsUnsavedWork: () -> Boolean,
) {
    val showLibrary: () -> Unit = { showLibraryUnlessWorkHeld(backStack, onSelectShellDestination, holdsUnsavedWork()) }
    when (action) {
        is ShortcutAction.Resume -> {
            // Get the most recent book and play it
            val result = homeRepository.getContinueListening(1)
            if (result is AppResult.Success && result.data.isNotEmpty()) {
                val book = result.data.first()
                logger.info { "Resuming book: ${book.title}" }
                playback.playBook(BookId(book.bookId))
                playback.expand()
            } else {
                logger.warn { "No recent book to resume" }
                showLibrary()
            }
        }

        is ShortcutAction.PlayBook -> {
            logger.info { "Playing book: ${action.bookId}" }
            playback.playBook(BookId(action.bookId))
            playback.expand()
        }

        is ShortcutAction.Search -> {
            if (showLibraryUnlessWorkHeld(backStack, onSelectShellDestination, holdsUnsavedWork())) onOpenSearch()
        }

        is ShortcutAction.NavigateToBook,
        is ShortcutAction.NavigateToAbsImport,
        is ShortcutAction.NavigateToPendingApprovals,
        is ShortcutAction.NavigateToUserProfile,
        -> {
            logger.info { "Navigating for shortcut: $action" }
            backStack.openFromOutside(Shell, action.screensAboveShell(), holdsUnsavedWork())
        }

        is ShortcutAction.SleepTimer -> {
            startSleepTimerFromShortcut(
                action = action,
                homeRepository = homeRepository,
                playback = playback,
                showLibrary = showLibrary,
            )
        }
    }
}

/** Minutes the launcher's "Set sleep timer" shortcut uses when it carries no preset of its own. */
private const val DEFAULT_SHORTCUT_SLEEP_MINUTES = 30

/** How long the sleep-timer shortcut waits for the book it started to become the player's current one. */
private val SHORTCUT_BOOK_LOAD_TIMEOUT = 10.seconds

/**
 * Sets the sleep timer the launcher's "Set sleep timer" shortcut promises, resuming the latest book
 * first when nothing is loaded.
 *
 * The timer is set only once the book is current: changing the player's book cancels a running
 * timer, so one set before the book loads would be cancelled a moment later.
 */
private suspend fun startSleepTimerFromShortcut(
    action: ShortcutAction.SleepTimer,
    homeRepository: HomeRepository,
    playback: ShortcutPlayback,
    showLibrary: () -> Unit,
) {
    val timer = SleepTimerMode.Duration(action.timerMinutes ?: DEFAULT_SHORTCUT_SLEEP_MINUTES)
    if (playback.activeBookId != null) {
        playback.setSleepTimer(timer)
        playback.expand()
        return
    }

    val latest = homeRepository.getContinueListening(1)
    val recent = if (latest is AppResult.Success) latest.data.firstOrNull() else null
    if (recent == null) {
        logger.warn { "No recent book to start a sleep timer on" }
        showLibrary()
        return
    }

    playback.playBook(BookId(recent.bookId))
    playback.expand()
    val current =
        withTimeoutOrNull(SHORTCUT_BOOK_LOAD_TIMEOUT) {
            playback.activeBookIds.first { it == recent.bookId }
        }
    if (current == null) {
        logger.warn { "Book ${recent.bookId} did not become current in time; no sleep timer set" }
        return
    }
    playback.setSleepTimer(timer)
}

/**
 * The screens a navigating [ShortcutAction] stacks on the shell, bottom first — empty for the
 * actions that play rather than navigate.
 *
 * The last hop of every notification tap, shade tap and launcher shortcut: the target mapping
 * before it (`toShortcutAction`) has its own specs, and this is the half that decides which screen
 * actually opens.
 */
internal fun ShortcutAction.screensAboveShell(): List<Route> =
    when (this) {
        is ShortcutAction.NavigateToBook -> listOf(BookDetail(bookId))

        is ShortcutAction.NavigateToAbsImport -> listOf(AdminBackups, ImportFlow)

        // Admin carries the pending list and its approve/deny controls — the point of the
        // notification is to make the decision reachable in one tap, so it opens on that list.
        is ShortcutAction.NavigateToPendingApprovals -> listOf(Admin(focus = AdminFocus.PENDING_REGISTRATIONS))

        is ShortcutAction.NavigateToUserProfile -> listOf(UserProfile(userId))

        ShortcutAction.Resume,
        is ShortcutAction.PlayBook,
        ShortcutAction.Search,
        is ShortcutAction.SleepTimer,
        -> emptyList()
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
    deepLinkManager: DeepLinkManager = koinInject(),
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
    var profileRefreshKey by rememberSaveable { mutableStateOf(0) }

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
        deepLinkManager = deepLinkManager,
        serverConfig = serverConfig,
        backStack = backStack,
        snackbarHostState = snackbarHostState,
        holdsUnsavedWork = { unsavedWork.isHeld },
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
        ProvideNowPlayingInsets(barVisible = barVisible, latchedFootprint = latchedFootprint) {
            CompositionLocalProvider(
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
 * Resolves a pending BOOK share-link target against the connected server. Invite targets are
 * handled at the top level; this ignores them.
 *
 * Extracted from [AuthenticatedNavigation] to keep the orchestrator within complexity budget.
 */
@Composable
private fun ShareLinkEffect(
    deepLinkManager: DeepLinkManager,
    serverConfig: ServerConfig,
    backStack: NavBackStack<NavKey>,
    snackbarHostState: SnackbarHostState,
    holdsUnsavedWork: () -> Boolean,
) {
    val pendingTarget by deepLinkManager.pendingTarget.collectAsStateWithLifecycle()
    val wrongServerMessage = stringResource(Res.string.error_book_wrong_server)
    val notConnectedMessage = stringResource(Res.string.error_book_not_connected)
    LaunchedEffect(pendingTarget) {
        val book = pendingTarget as? ShareTarget.Book ?: return@LaunchedEffect
        when (val resolution = ShareTargetResolver.resolve(book, serverConfig.getConnectedServerId())) {
            is ShareResolution.OpenBook -> {
                backStack.openFromOutside(Shell, listOf(BookDetail(resolution.bookId.value)), holdsUnsavedWork())
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
}

/**
 * The app's root surface, answering the hardware-keyboard shortcuts ([ShellKeyboardShortcuts]) with
 * the player and the shell's search. The search requests the `/` shortcut makes reach the shell
 * through [LocalShellSearchRequests]. Not on TV, where the D-pad's arrows and centre key must only
 * move focus.
 */
@Composable
private fun AppKeyboardShortcuts(
    nowPlayingViewModel: PlayerViewModel,
    backStack: NavBackStack<NavKey>,
    searchRequests: ShellSearchRequests,
    content: @Composable BoxScope.() -> Unit,
) {
    val playerState by nowPlayingViewModel.uiState.collectAsStateWithLifecycle()
    val playerExpanded by nowPlayingViewModel.expanded.collectAsStateWithLifecycle()
    ShellKeyboardShortcuts(
        enabled = !LocalDeviceContext.current.isLeanback,
        onShortcut = { shortcut ->
            answerShellShortcut(
                shortcut = shortcut,
                nowPlayingViewModel = nowPlayingViewModel,
                hasBook = playerState is PlayerUiState.Loaded,
                canOpenSearch = backStack.lastOrNull() == Shell && !playerExpanded,
                searchRequests = searchRequests,
            )
        },
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
    ) {
        CompositionLocalProvider(LocalShellSearchRequests provides searchRequests.requests) {
            content()
        }
    }
}

/**
 * Acts on a keyboard [shortcut], returning whether it did. The transport shortcuts need a loaded
 * book, and skip by the user's configured intervals (the view model's own [PlayerViewModel.skipBack]
 * and [PlayerViewModel.skipForward]). Search opens only where the shell's search is on screen.
 */
private fun answerShellShortcut(
    shortcut: ShellShortcut,
    nowPlayingViewModel: PlayerViewModel,
    hasBook: Boolean,
    canOpenSearch: Boolean,
    searchRequests: ShellSearchRequests,
): Boolean =
    when (shortcut) {
        ShellShortcut.PlayPause -> hasBook.also { if (it) nowPlayingViewModel.playPause() }
        ShellShortcut.SkipBack -> hasBook.also { if (it) nowPlayingViewModel.skipBack() }
        ShellShortcut.SkipForward -> hasBook.also { if (it) nowPlayingViewModel.skipForward() }
        ShellShortcut.OpenSearch -> canOpenSearch.also { if (it) searchRequests.request() }
    }

/**
 * Builds the dispatch lambda the notification inbox entries consume: every action routes through
 * [handleShortcutAction] — the SAME handler the shade's [ShortcutActionEffect] uses — so the two
 * entry points cannot disagree on where a notification lands.
 */
private fun shortcutActionDispatcher(
    scope: CoroutineScope,
    homeRepository: HomeRepository,
    nowPlayingViewModel: PlayerViewModel,
    backStack: NavBackStack<NavKey>,
    onSelectShellDestination: (ShellDestination) -> Unit,
    onOpenSearch: () -> Unit,
    holdsUnsavedWork: () -> Boolean,
): (ShortcutAction) -> Unit =
    { action ->
        scope.launch {
            handleShortcutAction(
                action = action,
                homeRepository = homeRepository,
                playback = nowPlayingViewModel.asShortcutPlayback(),
                backStack = backStack,
                onSelectShellDestination = onSelectShellDestination,
                onOpenSearch = onOpenSearch,
                holdsUnsavedWork = holdsUnsavedWork,
            )
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
    // Now Playing overlay - persistent across all navigation
    // Position adjusts based on whether bottom nav is visible (Shell vs detail screens)
    // Also animates up when snackbar is visible
    // Pass viewModel explicitly to ensure NowPlayingHost shares the same instance
    // as NowPlayingBar in AppShell - prevents state divergence between screens
    NowPlayingHost(
        isShellShowing = backStack.lastOrNull() == Shell,
        isReaderShowing = backStack.lastOrNull() is DocumentViewer,
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
        // The same routes the lapsed-session banner and the admin Inbox entry use.
        onOpenSignIn = {
            if (backStack.lastOrNull() != Login) backStack.add(Login)
        },
        onOpenInbox = { backStack.add(AdminInbox) },
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
    nowPlayingViewModel: PlayerViewModel,
    backStack: NavBackStack<NavKey>,
    onSelectShellDestination: (ShellDestination) -> Unit,
    onOpenSearch: () -> Unit,
    holdsUnsavedWork: () -> Boolean,
) {
    val pendingAction by shortcutActionManager.pendingAction.collectAsStateWithLifecycle()
    LaunchedEffect(pendingAction) {
        val action = pendingAction ?: return@LaunchedEffect

        logger.info { "Processing shortcut action: $action" }

        handleShortcutAction(
            action = action,
            homeRepository = homeRepository,
            playback = nowPlayingViewModel.asShortcutPlayback(),
            backStack = backStack,
            onSelectShellDestination = onSelectShellDestination,
            onOpenSearch = onOpenSearch,
            holdsUnsavedWork = holdsUnsavedWork,
        )

        // Consume the action after processing
        shortcutActionManager.consumeAction()
    }
}
