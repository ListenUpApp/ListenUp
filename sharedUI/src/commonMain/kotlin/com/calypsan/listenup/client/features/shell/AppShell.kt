package com.calypsan.listenup.client.features.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.SyncStatusRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.features.shell.components.AppNavigationSuite
import com.calypsan.listenup.client.features.shell.components.AppTopBar

import com.calypsan.listenup.client.presentation.search.SearchNavAction
import com.calypsan.listenup.client.features.search.SearchResultsOverlay
import com.calypsan.listenup.client.presentation.search.SearchViewModel
import androidx.compose.runtime.saveable.rememberSaveable
import com.calypsan.listenup.client.presentation.sync.SyncIndicatorUiEvent
import com.calypsan.listenup.client.presentation.sync.SyncIndicatorViewModel
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.client.features.shell.components.GlobalErrorSnackbar
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.shell_library_changed
import listenup.composeapp.generated.resources.shell_the_servers_library_has_changed

private val logger = KotlinLogging.logger {}

/**
 * Main app shell providing persistent navigation frame.
 *
 * Contains:
 * - Top bar with collapsible search, sync indicator, user avatar
 * - Content area switching between Home, Library, Discover
 * - Reserved slot for future Now Playing bar
 * - Bottom navigation bar (compact), navigation rail (medium), or drawer (expanded)
 *
 * The shell is platform-agnostic. Content for each destination is provided via lambdas,
 * allowing platform-specific navigation to supply the actual screen implementations.
 *
 * @param currentDestination Current bottom nav tab (state lifted to survive navigation)
 * @param onDestinationChange Callback when bottom nav tab changes
 * @param onBookClick Callback when a book is clicked (navigates to detail)
 * @param onSeriesClick Callback when a series is clicked (navigates to detail)
 * @param onContributorClick Callback when a contributor is clicked (author or narrator)
 * @param onTagClick Callback when a tag is clicked
 * @param onAdminClick Callback when administration is clicked (only shown for admin users)
 * @param onSettingsClick Callback when settings is clicked
 * @param onSignOut Callback when sign out is triggered
 * @param onUserProfileClick Callback when a user profile is clicked
 * @param homeContent Content composable for Home destination
 * @param libraryContent Content composable for Library destination
 * @param discoverContent Content composable for Discover destination
 */
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod", "CognitiveComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    currentDestination: ShellDestination,
    onDestinationChange: (ShellDestination) -> Unit,
    onBookClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onContributorClick: (String) -> Unit,
    onTagClick: (String) -> Unit,
    onAdminClick: (() -> Unit)? = null,
    onSettingsClick: () -> Unit,
    onSignOut: () -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
    homeContent: @Composable (PaddingValues, topBarCollapseFraction: Float, onNavigateToLibrary: () -> Unit) -> Unit,
    libraryContent: @Composable (PaddingValues, topBarCollapseFraction: Float) -> Unit,
    nowPlayingContent: @Composable () -> Unit = {},
    discoverContent: @Composable (PaddingValues) -> Unit,
) {
    // Inject dependencies
    val syncRepository: SyncRepository = koinInject()
    val userRepository: UserRepository = koinInject()
    val syncStatusRepository: SyncStatusRepository = koinInject()
    val authSession: AuthSession = koinInject()
    val downloadService: DownloadService = koinInject()
    val searchViewModel: SearchViewModel = koinViewModel()
    val syncIndicatorViewModel: SyncIndicatorViewModel = koinViewModel()
    val snackbarHostState = remember { SnackbarHostState() }

    // Trigger sync on shell entry (not just when Library is visible)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val isAuthenticated = authSession.getAccessToken() != null
        val lastSyncTime = syncStatusRepository.getLastSyncTime()
        if (isAuthenticated && lastSyncTime == null) {
            syncRepository.sync()
        } else if (isAuthenticated) {
            // Already synced before — just reconnect SSE and delta sync
            syncRepository.connectRealtime()
        }

        // Resume any stalled/interrupted downloads after (re-)authentication
        if (isAuthenticated) {
            downloadService.resumeIncompleteDownloads()
        }
    }

    // Fetch user data if missing from database but authenticated
    LaunchedEffect(Unit) {
        val hasTokens = authSession.getAccessToken() != null
        val existingUser = userRepository.getCurrentUser()

        if (hasTokens && existingUser == null) {
            logger.info { "User data missing but authenticated, fetching from server..." }
            userRepository.refreshCurrentUser()
        }
    }

    // Collect reactive state - use collectAsState for multiplatform compatibility
    val syncState by syncRepository.syncState.collectAsStateWithLifecycle()
    val user by userRepository.observeCurrentUser().collectAsStateWithLifecycle(initialValue = null)
    val searchState by searchViewModel.state.collectAsStateWithLifecycle()
    val syncIndicatorState by syncIndicatorViewModel.state.collectAsStateWithLifecycle()
    val isSyncDetailsExpanded by syncIndicatorViewModel.isExpanded.collectAsStateWithLifecycle()

    // Search overlay expansion lives in the UI — purely presentational state.
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }

    // Handle search navigation: collapse the overlay and clear the query before navigating away.
    LaunchedEffect(searchViewModel) {
        searchViewModel.navActions.collect { action ->
            isSearchExpanded = false
            searchViewModel.clearQuery()
            when (action) {
                is SearchNavAction.NavigateToBook -> onBookClick(action.bookId)
                is SearchNavAction.NavigateToContributor -> onContributorClick(action.contributorId)
                is SearchNavAction.NavigateToSeries -> onSeriesClick(action.seriesId)
                is SearchNavAction.NavigateToTag -> onTagClick(action.tagId)
            }
        }
    }

    // Local UI state
    var isAvatarMenuExpanded by remember { mutableStateOf(false) }

    // Library mismatch dialog state
    var libraryMismatchToShow by remember { mutableStateOf<SyncState.LibraryMismatch?>(null) }

    // Detect library mismatch from sync state
    LaunchedEffect(syncState) {
        if (syncState is SyncState.LibraryMismatch) {
            libraryMismatchToShow = syncState as SyncState.LibraryMismatch
        }
    }

    // Library mismatch dialog
    libraryMismatchToShow?.let { mismatch ->
        ListenUpDestructiveDialog(
            onDismissRequest = { libraryMismatchToShow = null },
            title = stringResource(Res.string.shell_library_changed),
            text =
                if (mismatch.hasPendingChanges) {
                    stringResource(Res.string.shell_the_servers_library_has_changed) +
                        "Would you like to resync with the new library?"
                } else {
                    "The server's library has changed. Your local data will be refreshed to match."
                },
            confirmText = if (mismatch.hasPendingChanges) "Discard & Resync" else "Resync",
            onConfirm = {
                libraryMismatchToShow = null
                scope.launch {
                    syncRepository.resetForNewLibrary(mismatch.actualLibraryId)
                }
            },
            dismissText = stringResource(Res.string.common_cancel),
            onDismiss = { libraryMismatchToShow = null },
        )
    }

    // Global error snackbar — collects from ErrorBus and displays
    GlobalErrorSnackbar(
        snackbarHostState = snackbarHostState,
        onRetry = { error ->
            if (error is SyncError.RealtimeDisconnected) {
                scope.launch { syncRepository.connectRealtime() }
            }
        },
    )

    // Scroll behavior for collapsing top bar
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // Derive collapse fraction for child components (0 = expanded, 1 = fully collapsed)
    val topBarCollapseFraction by remember {
        derivedStateOf {
            val limit = scrollBehavior.state.heightOffsetLimit
            if (limit != 0f) {
                scrollBehavior.state.heightOffset / limit
            } else {
                0f
            }
        }
    }

    // Adaptive navigation surface for the current window size.
    val navType = shellNavType(currentWindowAdaptiveInfo().windowSizeClass)

    // The account menu lives in the top bar at every size now.
    val showAvatarInTopBar = true

    // Common top bar configuration
    val topBar: @Composable () -> Unit = {
        AppTopBar(
            currentDestination = currentDestination,
            syncState = syncState,
            user = user,
            isSearchExpanded = isSearchExpanded,
            searchQuery = searchState.query,
            onSearchExpandedChange = { expanded ->
                isSearchExpanded = expanded
                if (!expanded) searchViewModel.clearQuery()
            },
            onSearchQueryChange = { query ->
                searchViewModel.onQueryChanged(query)
            },
            isAvatarMenuExpanded = isAvatarMenuExpanded,
            onAvatarMenuExpandedChange = { isAvatarMenuExpanded = it },
            onAdminClick = onAdminClick,
            onSettingsClick = onSettingsClick,
            onSignOutClick = onSignOut,
            onMyProfileClick = { user?.id?.value?.let(onUserProfileClick) },
            onSyncIndicatorClick = { syncIndicatorViewModel.toggleExpanded() },
            isSyncDetailsExpanded = isSyncDetailsExpanded,
            syncIndicatorUiState = syncIndicatorState,
            onRetryOperation = { id ->
                syncIndicatorViewModel.onEvent(SyncIndicatorUiEvent.RetryOperation(id))
            },
            onDismissOperation = { id ->
                syncIndicatorViewModel.onEvent(SyncIndicatorUiEvent.DismissOperation(id))
            },
            onRetryAll = {
                syncIndicatorViewModel.onEvent(SyncIndicatorUiEvent.RetryAll)
            },
            onDismissAll = {
                syncIndicatorViewModel.onEvent(SyncIndicatorUiEvent.DismissAll)
            },
            onSyncDetailsDismiss = { syncIndicatorViewModel.toggleExpanded() },
            scrollBehavior = scrollBehavior,
            showAvatar = showAvatarInTopBar,
        )
    }

    // Common content configuration
    val shellContent: @Composable (PaddingValues) -> Unit = { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Content based on current destination
            when (currentDestination) {
                ShellDestination.Home -> {
                    homeContent(
                        padding,
                        topBarCollapseFraction,
                        { onDestinationChange(ShellDestination.Library) },
                    )
                }

                ShellDestination.Library -> {
                    libraryContent(padding, topBarCollapseFraction)
                }

                ShellDestination.Discover -> {
                    discoverContent(padding)
                }
            }

            // Search results overlay (floats above content when search is active)
            SearchResultsOverlay(
                state = searchState,
                isExpanded = isSearchExpanded,
                onResultClick = { hit ->
                    searchViewModel.onResultClicked(hit)
                },
                onTypeFilterToggle = { type ->
                    searchViewModel.toggleTypeFilter(type)
                },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
            )
        }
    }

    if (navType == ShellNavType.BottomBar) {
        // Phone layout: bottom navigation, mini-player stacked above it.
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = topBar,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Column {
                    nowPlayingContent()
                    AppNavigationSuite(
                        navType = navType,
                        currentDestination = currentDestination,
                        onDestinationSelected = onDestinationChange,
                    )
                }
            },
            content = shellContent,
        )
    } else {
        // Tablet / desktop layout: wide rail beside the content pane.
        // The mini-player is NOT rendered here — on these device types NowPlayingHost
        // already shows a root-level DockedNowPlayingBar, so adding nowPlayingContent()
        // would double it. The compact branch keeps the stacked mini-player above the bar.
        Row(modifier = Modifier.fillMaxSize()) {
            AppNavigationSuite(
                navType = navType,
                currentDestination = currentDestination,
                onDestinationSelected = onDestinationChange,
            )
            Scaffold(
                modifier =
                    Modifier
                        .weight(1f)
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                topBar = topBar,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                content = shellContent,
            )
        }
    }
}
