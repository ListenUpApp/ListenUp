@file:Suppress("CyclomaticComplexMethod", "UnusedParameter")

package com.calypsan.listenup.client.navigation

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.repository.DeepLinkManager
import com.calypsan.listenup.client.data.repository.ShortcutAction
import com.calypsan.listenup.client.data.repository.ShortcutActionManager
import com.calypsan.listenup.client.data.sync.LibraryResetHelperContract
import com.calypsan.listenup.client.domain.repository.SyncRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.features.admin.AdminScreen
import com.calypsan.listenup.client.features.admin.CreateInviteScreen
import com.calypsan.listenup.client.features.admin.backup.AdminBackupScreen
import com.calypsan.listenup.client.features.admin.backup.ABSImportHubDetailScreen
import com.calypsan.listenup.client.features.admin.backup.ABSImportScreen
import com.calypsan.listenup.client.features.admin.backup.CreateBackupScreen
import com.calypsan.listenup.client.features.admin.backup.RestoreBackupScreen
import com.calypsan.listenup.client.features.connect.ServerSelectScreen
import com.calypsan.listenup.client.features.connect.ServerSetupScreen
import com.calypsan.listenup.client.features.invite.InviteRegistrationScreen
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.features.nowplaying.NowPlayingBar
import com.calypsan.listenup.client.features.nowplaying.NowPlayingHost
import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingViewModel
import com.calypsan.listenup.client.features.discover.DiscoverScreen
import com.calypsan.listenup.client.features.home.HomeScreen
import com.calypsan.listenup.client.features.library.LibraryScreen
import com.calypsan.listenup.client.features.settings.SettingsScreen
import com.calypsan.listenup.client.features.setup.LibrarySetupScreen
import com.calypsan.listenup.client.features.shell.AppShell
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.presentation.library.LibraryViewModel
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesViewModel
import com.calypsan.listenup.client.presentation.admin.AdminInboxViewModel
import com.calypsan.listenup.client.presentation.browsegenre.BrowseGenreViewModel
import com.calypsan.listenup.client.presentation.unmappedgenres.UnmappedGenresViewModel
import com.calypsan.listenup.client.presentation.admin.AdminSettingsUiState
import com.calypsan.listenup.client.presentation.admin.AdminSettingsViewModel
import com.calypsan.listenup.client.presentation.admin.AdminViewModel
import com.calypsan.listenup.client.presentation.admin.CreateInviteViewModel
import com.calypsan.listenup.client.presentation.auth.PendingApprovalViewModel
import com.calypsan.listenup.client.presentation.invite.InviteRegistrationUiState
import com.calypsan.listenup.client.presentation.invite.InviteRegistrationViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.calypsan.listenup.client.presentation.startup.AppStartupViewModel
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.core.Success

private val logger = KotlinLogging.logger {}

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

    // Observe pending invite deep link
    val pendingInvite by deepLinkManager.pendingInvite.collectAsStateWithLifecycle()

    // Observe auth state changes
    val authState by authSession.authState.collectAsStateWithLifecycle()

    // Check for pending invite BEFORE auth state routing
    // This allows invite registration even when already authenticated
    pendingInvite?.let { invite ->
        InviteRegistrationNavigation(
            serverUrl = invite.serverUrl,
            inviteCode = invite.code,
            onComplete = { deepLinkManager.consumeInvite() },
            onCancel = { deepLinkManager.consumeInvite() },
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

        is AuthState.Authenticated -> {
            AuthenticatedNavigation(authSession)
        }
    }
}

/**
 * Navigation for pending approval screen.
 *
 * Shows the pending approval screen for users who have registered
 * but are waiting for admin approval. Handles:
 * - SSE connection for real-time approval notification
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
 * Navigation for invite registration flow.
 *
 * Shows the invite registration screen and handles completion/cancellation.
 * On successful registration, auth tokens are stored and AuthState becomes Authenticated,
 * which will trigger navigation to the library after the invite is consumed.
 */
@Composable
private fun InviteRegistrationNavigation(
    serverUrl: String,
    inviteCode: String,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
) {
    val viewModel: InviteRegistrationViewModel =
        koinViewModel {
            org.koin.core.parameter
                .parametersOf(serverUrl, inviteCode)
        }

    // Watch for successful registration to trigger completion
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state is InviteRegistrationUiState.Submitted) {
            onComplete()
        }
    }

    InviteRegistrationScreen(
        viewModel = viewModel,
        onCancel = onCancel,
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
                            // Pop back to select (will be replaced by auth screen)
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
@Suppress("LongMethod", "CognitiveComplexMethod")
@Composable
private fun AuthenticatedNavigation(
    authSession: AuthSession,
    libraryResetHelper: LibraryResetHelperContract = koinInject(),
    syncRepository: SyncRepository = koinInject(),
    shortcutActionManager: ShortcutActionManager = koinInject(),
    homeRepository: HomeRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()

    // ViewModels for shortcut action handling
    val nowPlayingViewModel: NowPlayingViewModel = koinViewModel()

    // AppStartupViewModel holds the library-setup check result across Activity re-creations.
    // This prevents the loading screen from reappearing on every config change or short
    // foreground resume. The ViewModel lives in the Activity's ViewModelStore, so it is only
    // discarded on process death (true cold start).
    val startupViewModel: AppStartupViewModel = koinViewModel()
    val startupState by startupViewModel.state.collectAsStateWithLifecycle()

    // Hoist navigation state above the isChecking check so it survives loading periods.
    // rememberNavBackStack persists across configuration changes and process death
    // (rubric §Navigation rule); the back stack survives when isChecking toggles,
    // preventing navigation position from being lost on app resume.
    val backStack = rememberNavBackStack(Shell)

    // Track shell tab state here so it survives navigation to detail screens
    var currentShellDestination by remember { mutableStateOf<ShellDestination>(ShellDestination.Home) }

    // Navigate to LibrarySetup when the check completes and setup is needed
    LaunchedEffect(startupState.needsLibrarySetup, startupState.isChecking) {
        if (!startupState.isChecking && startupState.needsLibrarySetup) {
            backStack.clear()
            backStack.add(LibrarySetup)
        }
    }

    // Track profile refresh - incremented when profile is updated to trigger refresh
    var profileRefreshKey by remember { mutableStateOf(0) }

    // App-wide snackbar state - provided to all screens via CompositionLocal
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle pending shortcut actions
    val pendingAction by shortcutActionManager.pendingAction.collectAsStateWithLifecycle()
    LaunchedEffect(pendingAction) {
        val action = pendingAction ?: return@LaunchedEffect

        logger.info { "Processing shortcut action: $action" }

        when (action) {
            is ShortcutAction.Resume -> {
                // Get the most recent book and play it
                val result = homeRepository.getContinueListening(1)
                if (result is com.calypsan.listenup.core.Success && result.data.isNotEmpty()) {
                    val book = result.data.first()
                    logger.info { "Resuming book: ${book.title}" }
                    nowPlayingViewModel.playBook(BookId(book.bookId))
                    nowPlayingViewModel.expand()
                } else {
                    logger.warn { "No recent book to resume" }
                    // Navigate to library as fallback
                    if (backStack.lastOrNull() != Shell) {
                        backStack.clear()
                        backStack.add(Shell)
                    }
                    currentShellDestination = ShellDestination.Library
                }
            }

            is ShortcutAction.PlayBook -> {
                logger.info { "Playing book: ${action.bookId}" }
                nowPlayingViewModel.playBook(BookId(action.bookId))
                nowPlayingViewModel.expand()
            }

            is ShortcutAction.Search -> {
                // Navigate to library (search tab)
                if (backStack.lastOrNull() != Shell) {
                    backStack.clear()
                    backStack.add(Shell)
                }
                currentShellDestination = ShellDestination.Library
            }

            is ShortcutAction.NavigateToBook -> {
                logger.info { "Navigating to book: ${'$'}{action.bookId}" }
                // Ensure we're on Shell first, then navigate to book detail
                if (backStack.lastOrNull() != Shell) {
                    backStack.clear()
                    backStack.add(Shell)
                }
                backStack.add(BookDetail(action.bookId))
            }

            is ShortcutAction.SleepTimer -> {
                // If playing, show sleep timer; otherwise resume + set timer
                val result = homeRepository.getContinueListening(1)
                if (result is com.calypsan.listenup.core.Success && result.data.isNotEmpty()) {
                    val book = result.data.first()
                    nowPlayingViewModel.playBook(BookId(book.bookId))
                    nowPlayingViewModel.expand()
                    // Let the user interact with sleep timer in the player
                }
            }

            is ShortcutAction.NavigateToAbsImport -> {
                logger.info { "Navigating to ABS import: ${action.importId}" }
                if (backStack.lastOrNull() != Shell) {
                    backStack.clear()
                    backStack.add(Shell)
                }
                backStack.add(AdminBackups)
                backStack.add(ABSImportDetail(action.importId))
            }
        }

        // Consume the action after processing
        shortcutActionManager.consumeAction()
    }

    // Wrap navigation with NowPlayingHost for persistent mini player
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
                    entryProvider {
                        entry<Shell> {
                            // Preload library data by injecting LibraryViewModel early
                            @Suppress("UNUSED_VARIABLE")
                            val libraryViewModel: LibraryViewModel = koinViewModel()

                            // Get search state for overlay
                            AppShell(
                                currentDestination = currentShellDestination,
                                onDestinationChange = { currentShellDestination = it },
                                nowPlayingContent = {
                                    val nowPlayingScreenState by nowPlayingViewModel
                                        .screenState
                                        .collectAsStateWithLifecycle()
                                    NowPlayingBar(
                                        state = nowPlayingScreenState.state,
                                        isExpanded = nowPlayingScreenState.isExpanded,
                                        onTap = nowPlayingViewModel::expand,
                                        onPlayPause = nowPlayingViewModel::playPause,
                                        onSkipBack = { nowPlayingViewModel.skipBack() },
                                        onSkipForward = { nowPlayingViewModel.skipForward() },
                                    )
                                },
                                onBookClick = { bookId ->
                                    backStack.add(BookDetail(bookId))
                                },
                                onSeriesClick = { seriesId ->
                                    backStack.add(SeriesDetail(seriesId))
                                },
                                onContributorClick = { contributorId ->
                                    backStack.add(ContributorDetail(contributorId))
                                },
                                onShelfClick = { shelfId ->
                                    backStack.add(ShelfDetail(shelfId))
                                },
                                onTagClick = { tagId ->
                                    backStack.add(TagDetail(tagId))
                                },
                                onAdminClick =
                                    if (!LocalDeviceContext.current.isLeanback) {
                                        { backStack.add(Admin) }
                                    } else {
                                        null
                                    },
                                onSettingsClick = {
                                    backStack.add(Settings)
                                },
                                onSignOut = {
                                    scope.launch {
                                        // Clear library data before signing out
                                        // This ensures next login (same or different user) gets fresh data
                                        libraryResetHelper.clearLibraryData()
                                        authSession.clearAuthTokens()
                                    }
                                },
                                onUserProfileClick = { userId ->
                                    backStack.add(UserProfile(userId))
                                },
                                homeContent = { padding, _, onNavigateToLibrary ->
                                    HomeScreen(
                                        onBookClick = { bookId -> backStack.add(BookDetail(bookId)) },
                                        onNavigateToLibrary = onNavigateToLibrary,
                                        onShelfClick = { shelfId -> backStack.add(ShelfDetail(shelfId)) },
                                        onSeeAllShelves = onNavigateToLibrary,
                                        modifier = Modifier.padding(padding),
                                    )
                                },
                                libraryContent = { padding, topBarCollapseFraction ->
                                    LibraryScreen(
                                        onBookClick = { bookId -> backStack.add(BookDetail(bookId)) },
                                        onSeriesClick = { seriesId -> backStack.add(SeriesDetail(seriesId)) },
                                        onAuthorClick = { authorId -> backStack.add(ContributorDetail(authorId)) },
                                        onNarratorClick = { narratorId ->
                                            backStack.add(ContributorDetail(narratorId))
                                        },
                                        onBrowseGenresClick = { backStack.add(BrowseGenre) },
                                        topBarCollapseFraction = topBarCollapseFraction,
                                        modifier = Modifier.padding(padding),
                                    )
                                },
                                discoverContent = { padding ->
                                    DiscoverScreen(
                                        onShelfClick = { shelfId -> backStack.add(ShelfDetail(shelfId)) },
                                        onBookClick = { bookId -> backStack.add(BookDetail(bookId)) },
                                        onUserProfileClick = { userId -> backStack.add(UserProfile(userId)) },
                                        modifier = Modifier.padding(padding),
                                    )
                                },
                            )
                        }
                        entry<LibrarySetup> {
                            LibrarySetupScreen(
                                onSetupComplete = {
                                    // Trigger sync to pull newly scanned books
                                    scope.launch {
                                        logger.info { "Library setup complete, triggering sync" }
                                        syncRepository.sync()
                                    }
                                    // Navigate to main app, clearing library setup from back stack
                                    backStack.clear()
                                    backStack.add(Shell)
                                },
                            )
                        }
                        entry<UserProfile> { args ->
                            com.calypsan.listenup.client.features.profile.UserProfileScreen(
                                userId = args.userId,
                                onBack = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onEditClick = {
                                    backStack.add(EditProfile)
                                },
                                onBookClick = { bookId ->
                                    backStack.add(BookDetail(bookId))
                                },
                                onShelfClick = { shelfId ->
                                    backStack.add(ShelfDetail(shelfId))
                                },
                                onCreateShelfClick = {
                                    backStack.add(CreateShelf)
                                },
                                refreshKey = profileRefreshKey,
                            )
                        }
                        entry<EditProfile> {
                            com.calypsan.listenup.client.features.profile.EditProfileScreen(
                                onBack = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onProfileUpdated = {
                                    profileRefreshKey++
                                },
                            )
                        }
                        entry<BookDetail> { args ->
                            com.calypsan.listenup.client.features.bookdetail.BookDetailScreen(
                                bookId = args.bookId,
                                onBackClick = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onEditClick = { bookId ->
                                    backStack.add(BookEdit(bookId))
                                },
                                onMetadataSearchClick = { bookId ->
                                    backStack.add(MetadataSearch(bookId))
                                },
                                onSeriesClick = { seriesId ->
                                    backStack.add(SeriesDetail(seriesId))
                                },
                                onContributorClick = { contributorId ->
                                    backStack.add(ContributorDetail(contributorId))
                                },
                                onTagClick = { tagId ->
                                    backStack.add(TagDetail(tagId))
                                },
                                onUserProfileClick = { userId ->
                                    backStack.add(UserProfile(userId))
                                },
                            )
                        }
                        entry<BookEdit> { args ->
                            com.calypsan.listenup.client.features.bookedit.BookEditScreen(
                                bookId = args.bookId,
                                onBackClick = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onSaveSuccess = {
                                    // Navigate back after successful save
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        entry<MetadataSearch> { args ->
                            com.calypsan.listenup.client.features.metadata.MetadataSearchRoute(
                                bookId = args.bookId,
                                onResultSelected = { asin ->
                                    backStack.add(MatchPreview(args.bookId, asin))
                                },
                                onBack = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        entry<MatchPreview> { args ->
                            com.calypsan.listenup.client.features.metadata.MatchPreviewRoute(
                                bookId = args.bookId,
                                asin = args.asin,
                                onBack = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onApplySuccess = {
                                    // Navigate back to book detail after successful apply
                                    // Pop both MatchPreview and MetadataSearch
                                    backStack.removeAt(backStack.lastIndex)
                                    if (backStack.lastOrNull() is MetadataSearch) {
                                        backStack.removeAt(backStack.lastIndex)
                                    }
                                },
                            )
                        }
                        entry<SeriesDetail> { args ->
                            com.calypsan.listenup.client.features.seriesdetail.SeriesDetailScreen(
                                seriesId = args.seriesId,
                                onBackClick = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onBookClick = { bookId ->
                                    backStack.add(BookDetail(bookId))
                                },
                                onEditClick = { seriesId ->
                                    backStack.add(SeriesEdit(seriesId))
                                },
                            )
                        }
                        entry<TagDetail> { args ->
                            com.calypsan.listenup.client.features.tagdetail.TagDetailScreen(
                                tagId = args.tagId,
                                onBackClick = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onBookClick = { bookId ->
                                    backStack.add(BookDetail(bookId))
                                },
                            )
                        }
                        entry<SeriesEdit> { args ->
                            com.calypsan.listenup.client.features.seriesedit.SeriesEditScreen(
                                seriesId = args.seriesId,
                                onBackClick = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onSaveSuccess = {
                                    // Navigate back after successful save
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        entry<ContributorDetail> { args ->
                            com.calypsan.listenup.client.features.contributordetail.ContributorDetailScreen(
                                contributorId = args.contributorId,
                                onBackClick = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onBookClick = { bookId ->
                                    backStack.add(BookDetail(bookId))
                                },
                                onEditClick = { contributorId ->
                                    backStack.add(ContributorEdit(contributorId))
                                },
                                onViewAllClick = { contributorId, role ->
                                    backStack.add(ContributorBooks(contributorId, role))
                                },
                                onMetadataClick = { contributorId ->
                                    backStack.add(ContributorMetadataSearch(contributorId))
                                },
                            )
                        }
                        entry<ContributorEdit> { args ->
                            com.calypsan.listenup.client.features.contributoredit.ContributorEditScreen(
                                contributorId = args.contributorId,
                                onBackClick = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onSaveSuccess = {
                                    // Navigate back after successful save
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        entry<ContributorBooks> { args ->
                            com.calypsan.listenup.client.features.contributordetail.ContributorBooksScreen(
                                contributorId = args.contributorId,
                                role = args.role,
                                onBackClick = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onBookClick = { bookId ->
                                    backStack.add(BookDetail(bookId))
                                },
                            )
                        }
                        entry<ContributorMetadataSearch> { args ->
                            com.calypsan.listenup.client.features.contributormetadata.ContributorMetadataSearchRoute(
                                contributorId = args.contributorId,
                                onCandidateSelected = { asin ->
                                    backStack.add(ContributorMetadataPreview(args.contributorId, asin))
                                },
                                onBack = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        entry<ContributorMetadataPreview> { args ->
                            com.calypsan.listenup.client.features.contributormetadata.ContributorMetadataPreviewRoute(
                                contributorId = args.contributorId,
                                asin = args.asin,
                                onApplySuccess = {
                                    // Pop both preview and search to go back to contributor detail
                                    backStack.removeAt(backStack.lastIndex)
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onChangeMatch = {
                                    // Pop preview to go back to search
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onBack = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        // Admin screens
                        entry<Admin> {
                            val viewModel: AdminViewModel = koinViewModel()
                            val settingsViewModel: AdminSettingsViewModel = koinViewModel()
                            val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
                            val readySettings = settingsState as? AdminSettingsUiState.Ready

                            AdminScreen(
                                viewModel = viewModel,
                                onBackClick = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onInviteClick = {
                                    backStack.add(CreateInvite)
                                },
                                onCollectionsClick = {
                                    backStack.add(AdminCollections)
                                },
                                onCategoriesClick = {
                                    backStack.add(AdminCategories)
                                },
                                onUnmappedGenresClick = {
                                    backStack.add(UnmappedGenres)
                                },
                                onInboxClick = {
                                    backStack.add(AdminInbox)
                                },
                                onBackupClick = {
                                    backStack.add(AdminBackups)
                                },
                                onUserClick = { userId ->
                                    backStack.add(AdminUserDetail(userId))
                                },
                                serverName = readySettings?.serverName ?: "",
                                onServerNameChange = { settingsViewModel.setServerName(it) },
                                remoteUrl = readySettings?.remoteUrl ?: "",
                                onRemoteUrlChange = { settingsViewModel.setRemoteUrl(it) },
                                inboxEnabled = readySettings?.inboxEnabled == true,
                                inboxCount = readySettings?.inboxCount ?: 0,
                                isSaving = readySettings?.isSaving == true,
                                onInboxEnabledChange = { settingsViewModel.setInboxEnabled(it) },
                                isDirty = readySettings?.isDirty == true,
                                onSave = { settingsViewModel.saveAll() },
                                settingsError =
                                    readySettings?.error
                                        ?: (settingsState as? AdminSettingsUiState.Error)?.message,
                                onClearSettingsError = { settingsViewModel.clearError() },
                            )

                            // Handle disable inbox confirmation dialog
                            if (readySettings?.showDisableConfirmation == true) {
                                val pendingCount = readySettings.inboxCount
                                com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog(
                                    onDismissRequest = { settingsViewModel.cancelDisableInbox() },
                                    title = "Disable Inbox Workflow",
                                    text =
                                        "This will release all $pendingCount " +
                                            "book${if (pendingCount != 1) "s" else ""} " +
                                            "currently in the inbox with their staged collection assignments.\n\n" +
                                            "New books will become immediately visible to users.",
                                    confirmText = "Disable & Release",
                                    onConfirm = { settingsViewModel.confirmDisableInbox() },
                                    onDismiss = { settingsViewModel.cancelDisableInbox() },
                                )
                            }
                        }
                        entry<AdminInbox> {
                            val viewModel: AdminInboxViewModel = koinViewModel()
                            com.calypsan.listenup.client.features.admin.inbox.AdminInboxScreen(
                                viewModel = viewModel,
                                onBackClick = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                // Tapping a row opens book-edit to fix tags/collections before release.
                                onBookClick = { bookId ->
                                    backStack.add(BookEdit(bookId))
                                },
                            )
                        }
                        entry<AdminCategories> {
                            val viewModel: AdminCategoriesViewModel = koinViewModel()
                            com.calypsan.listenup.client.features.admin.categories.AdminCategoriesScreen(
                                viewModel = viewModel,
                                onBackClick = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        entry<BrowseGenre> {
                            val viewModel: BrowseGenreViewModel = koinViewModel()
                            com.calypsan.listenup.client.features.browsegenre.BrowseGenreScreen(
                                viewModel = viewModel,
                                onBackClick = { backStack.removeAt(backStack.lastIndex) },
                                onBookClick = { bookId -> backStack.add(BookDetail(bookId.value)) },
                            )
                        }
                        entry<UnmappedGenres> {
                            val viewModel: UnmappedGenresViewModel = koinViewModel()
                            com.calypsan.listenup.client.features.unmappedgenres.UnmappedGenresScreen(
                                viewModel = viewModel,
                                onBackClick = { backStack.removeAt(backStack.lastIndex) },
                            )
                        }
                        entry<CreateInvite> {
                            val viewModel: CreateInviteViewModel = koinViewModel()
                            CreateInviteScreen(
                                viewModel = viewModel,
                                onBackClick = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onSuccess = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        entry<AdminCollections> {
                            val viewModel: com.calypsan.listenup.client.presentation.admin.AdminCollectionsViewModel =
                                koinViewModel()
                            com.calypsan.listenup.client.features.admin.collections.AdminCollectionsScreen(
                                viewModel = viewModel,
                                onBackClick = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onCollectionClick = { collectionId ->
                                    backStack.add(AdminCollectionDetail(collectionId))
                                },
                            )
                        }
                        entry<AdminCollectionDetail> { args ->
                            val viewModel:
                                com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailViewModel =
                                koinViewModel {
                                    org.koin.core.parameter
                                        .parametersOf(args.collectionId)
                                }
                            com.calypsan.listenup.client.features.admin.collections.AdminCollectionDetailScreen(
                                viewModel = viewModel,
                                onBackClick = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        entry<AdminUserDetail> { args ->
                            val viewModel:
                                com.calypsan.listenup.client.presentation.admin.UserDetailViewModel =
                                koinViewModel {
                                    org.koin.core.parameter
                                        .parametersOf(args.userId)
                                }
                            com.calypsan.listenup.client.features.admin.UserDetailScreen(
                                viewModel = viewModel,
                                onBackClick = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        entry<AdminLibrarySettings> { args ->
                            val viewModel:
                                com.calypsan.listenup.client.presentation.admin.LibrarySettingsViewModel =
                                koinViewModel {
                                    org.koin.core.parameter
                                        .parametersOf(args.libraryId)
                                }
                            com.calypsan.listenup.client.features.admin.LibrarySettingsScreen(
                                viewModel = viewModel,
                                onBackClick = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        entry<AdminBackups> {
                            AdminBackupScreen(
                                onBackClick = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onCreateClick = {
                                    backStack.add(CreateBackup)
                                },
                                onRestoreClick = { backupId ->
                                    backStack.add(RestoreBackup(backupId))
                                },
                                onABSImportHubClick = { importId ->
                                    backStack.add(ABSImportDetail(importId))
                                },
                            )
                        }
                        entry<CreateBackup> {
                            CreateBackupScreen(
                                onBackClick = { backStack.removeAt(backStack.lastIndex) },
                                onSuccess = {
                                    // Navigate back to backup list after successful creation
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        entry<RestoreBackup> { args ->
                            RestoreBackupScreen(
                                backupId = args.backupId,
                                onBackClick = { backStack.removeAt(backStack.lastIndex) },
                                onComplete = {
                                    // Navigate back to backup list after restore
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        // ABSImportList removed - imports are now shown inline in AdminBackupScreen
                        entry<ABSImportDetail> { args ->
                            ABSImportHubDetailScreen(
                                importId = args.importId,
                                onBackClick = { backStack.removeAt(backStack.lastIndex) },
                            )
                        }
                        entry<ABSImport> {
                            ABSImportScreen(
                                onBackClick = { backStack.removeAt(backStack.lastIndex) },
                                onComplete = {
                                    // Navigate back to backup list after import
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        entry<Settings> {
                            SettingsScreen(
                                showDynamicColors = true,
                                onNavigateBack = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onNavigateToStorage = {
                                    backStack.add(Storage)
                                },
                                onNavigateToLicenses = {
                                    backStack.add(Licenses)
                                },
                            )
                        }
                        entry<ShelfDetail> { args ->
                            com.calypsan.listenup.client.features.shelf.ShelfDetailScreen(
                                shelfId = args.shelfId,
                                onBack = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                onBookClick = { bookId ->
                                    backStack.add(BookDetail(bookId))
                                },
                                onEditClick = { shelfId ->
                                    backStack.add(ShelfEdit(shelfId))
                                },
                            )
                        }
                        entry<CreateShelf> {
                            com.calypsan.listenup.client.features.shelf.CreateEditShelfScreen(
                                shelfId = null,
                                onBack = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        entry<ShelfEdit> { args ->
                            com.calypsan.listenup.client.features.shelf.CreateEditShelfScreen(
                                shelfId = args.shelfId,
                                onBack = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        entry<Licenses> {
                            com.calypsan.listenup.client.features.settings.LicensesScreen(
                                onNavigateBack = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        entry<Storage> {
                            com.calypsan.listenup.client.features.settings.StorageScreen(
                                onNavigateBack = {
                                    backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                    },
            )

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
                viewModel = nowPlayingViewModel,
            )

            // App-wide snackbar - positioned at bottom, mini player animates up when visible
            SnackbarHost(
                hostState = snackbarHostState,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
            )

            // Overlay loading indicator while startup check is in progress.
            // Using an overlay instead of early return preserves the backStack state.
            if (startupState.isChecking) {
                FullScreenLoadingIndicator()
            }
        }
    }
}
