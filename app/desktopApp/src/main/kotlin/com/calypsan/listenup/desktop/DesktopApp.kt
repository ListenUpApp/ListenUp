package com.calypsan.listenup.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.domain.model.FacetKind
import com.calypsan.listenup.client.domain.usecase.auth.LogoutUseCase
import com.calypsan.listenup.client.features.bookdetail.BookDetailScreen
import com.calypsan.listenup.client.features.bookedit.BookEditScreen
import com.calypsan.listenup.client.features.browsefacet.FacetBooksScreen
import com.calypsan.listenup.client.features.admin.AdminScreen
import com.calypsan.listenup.client.features.admin.CreateInviteScreen
import com.calypsan.listenup.client.features.admin.UserDetailScreen
import com.calypsan.listenup.client.features.admin.collections.AdminCollectionDetailScreen
import com.calypsan.listenup.client.features.admin.collections.AdminCollectionsScreen
import com.calypsan.listenup.client.features.admin.inbox.AdminInboxScreen
import com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailViewModel
import com.calypsan.listenup.client.presentation.admin.AdminCollectionsViewModel
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesViewModel
import com.calypsan.listenup.client.features.admin.categories.AdminCategoriesScreen
import com.calypsan.listenup.client.presentation.admin.AdminInboxViewModel
import com.calypsan.listenup.client.presentation.admin.AdminSettingsUiState
import com.calypsan.listenup.client.presentation.admin.AdminSettingsViewModel
import com.calypsan.listenup.client.presentation.admin.AdminViewModel
import com.calypsan.listenup.client.presentation.admin.CreateInviteViewModel
import com.calypsan.listenup.client.presentation.admin.UserDetailViewModel
import com.calypsan.listenup.client.features.discover.DiscoverScreen
import com.calypsan.listenup.client.features.genredestination.GenreDestinationScreen
import com.calypsan.listenup.client.features.home.HomeScreen
import com.calypsan.listenup.client.features.contributordetail.ContributorBooksScreen
import com.calypsan.listenup.client.features.contributordetail.ContributorDetailScreen
import com.calypsan.listenup.client.features.contributoredit.ContributorEditScreen
import com.calypsan.listenup.client.features.contributormetadata.ContributorMetadataPreviewRoute
import com.calypsan.listenup.client.features.contributormetadata.ContributorMetadataSearchRoute
import com.calypsan.listenup.client.features.shelf.CreateEditShelfScreen
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.features.metadata.MatchPreviewRoute
import com.calypsan.listenup.client.features.metadata.MetadataSearchRoute
import com.calypsan.listenup.client.features.shelf.ShelfDetailScreen
import com.calypsan.listenup.client.features.library.LibraryScreen
import com.calypsan.listenup.client.features.seriesdetail.SeriesDetailScreen
import com.calypsan.listenup.client.features.seriesedit.SeriesEditScreen
import com.calypsan.listenup.client.features.settings.LicenseDetailScreen
import com.calypsan.listenup.client.features.settings.LicensesScreen
import com.calypsan.listenup.client.features.settings.SettingsScreen
import com.calypsan.listenup.client.features.shell.AppShell
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.features.profile.UserProfileScreen
import com.calypsan.listenup.client.navigation.AuthNavigation
import com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetViewModel
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.genredestination.GenreDestinationViewModel
import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingViewModel
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.desktop.nowplaying.DesktopNowPlayingBar
import com.calypsan.listenup.desktop.nowplaying.DesktopNowPlayingScreen
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

private val logger = KotlinLogging.logger {}

/**
 * Detail screen destinations for the desktop back stack.
 */
sealed interface DetailDestination {
    data class Book(
        val bookId: String,
    ) : DetailDestination

    data class Series(
        val seriesId: String,
    ) : DetailDestination

    data class Contributor(
        val contributorId: String,
    ) : DetailDestination

    data class Shelf(
        val shelfId: String,
    ) : DetailDestination

    data class Tag(
        val tagId: String,
        val tagName: String,
    ) : DetailDestination

    data class Mood(
        val moodId: String,
        val moodName: String,
    ) : DetailDestination

    data class Genre(
        val genreId: String,
    ) : DetailDestination

    data class BookEdit(
        val bookId: String,
    ) : DetailDestination

    data class ContributorEdit(
        val contributorId: String,
    ) : DetailDestination

    data class SeriesEdit(
        val seriesId: String,
    ) : DetailDestination

    data class ShelfEdit(
        val shelfId: String,
    ) : DetailDestination

    data object ShelfCreate : DetailDestination

    data class MetadataSearch(
        val bookId: String,
    ) : DetailDestination

    data class MatchPreview(
        val bookId: String,
        val asin: String,
        val region: MetadataLocale,
    ) : DetailDestination

    data class ContributorMetadataSearch(
        val contributorId: String,
    ) : DetailDestination

    data class ContributorMetadataPreview(
        val contributorId: String,
        val asin: String,
        val region: MetadataLocale,
    ) : DetailDestination

    data object Settings : DetailDestination

    data object Licenses : DetailDestination

    data class LicenseDetail(
        val uniqueId: String,
    ) : DetailDestination

    data object Storage : DetailDestination

    data object NowPlaying : DetailDestination

    data object Admin : DetailDestination

    data object CreateInvite : DetailDestination

    data class UserDetail(
        val userId: String,
    ) : DetailDestination

    data object AdminCollections : DetailDestination

    data class AdminCollectionDetail(
        val collectionId: String,
    ) : DetailDestination

    data class UserProfile(
        val userId: String,
    ) : DetailDestination

    data class ContributorBooks(
        val contributorId: String,
        val role: String,
    ) : DetailDestination

    data object AdminCategories : DetailDestination

    data object AdminInbox : DetailDestination
}

/**
 * Root composable for the desktop application.
 *
 * Handles:
 * - Authentication flow via shared AuthNavigation
 * - Navigation to main app shell after authentication
 * - Detail screen navigation via back stack overlay
 */
@Composable
fun DesktopApp() {
    var isAuthenticated by remember { mutableStateOf(false) }

    if (!isAuthenticated) {
        AuthNavigation(
            onAuthenticated = {
                isAuthenticated = true
            },
        )
    } else {
        DesktopAuthenticatedNavigation()
    }
}

/**
 * Navigation for authenticated desktop users.
 *
 * Uses a back stack to overlay detail screens on top of the shell.
 * When the back stack is empty, the shell is shown. When non-empty,
 * the top destination is rendered as the current screen.
 */
@Composable
private fun DesktopAuthenticatedNavigation() {
    val scope = rememberCoroutineScope()
    val logoutUseCase: LogoutUseCase = koinInject()
    val nowPlayingViewModel: NowPlayingViewModel = koinInject()
    val snackbarHostState = remember { SnackbarHostState() }

    val playerScreenState by nowPlayingViewModel.screenState.collectAsStateWithLifecycle()
    val playerState = playerScreenState.state
    val playerProgressState = nowPlayingViewModel.progress.collectAsStateWithLifecycle()

    var currentDestination by remember { mutableStateOf<ShellDestination>(ShellDestination.Home) }
    val backStack: SnapshotStateList<DetailDestination> =
        remember { emptyList<DetailDestination>().toMutableStateList() }

    val navigateTo: (DetailDestination) -> Unit = { backStack.add(it) }
    val navigateBack: () -> Unit = { backStack.removeLastOrNull() }

    val isShowingNowPlaying = backStack.lastOrNull() is DetailDestination.NowPlaying

    val deviceContext: DeviceContext = koinInject()
    CompositionLocalProvider(
        LocalSnackbarHostState provides snackbarHostState,
        LocalDeviceContext provides deviceContext,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                if (backStack.isNotEmpty()) {
                    DetailScreen(
                        destination = backStack.last(),
                        navigateTo = navigateTo,
                        navigateBack = navigateBack,
                        nowPlayingViewModel = nowPlayingViewModel,
                    )
                } else {
                    AppShell(
                        currentDestination = currentDestination,
                        onDestinationChange = { currentDestination = it },
                        onBookClick = { navigateTo(DetailDestination.Book(it)) },
                        onSeriesClick = { navigateTo(DetailDestination.Series(it)) },
                        onContributorClick = { navigateTo(DetailDestination.Contributor(it)) },
                        onTagClick = { tagId, tagName ->
                            navigateTo(DetailDestination.Tag(tagId, tagName))
                        },
                        onAdminClick = { navigateTo(DetailDestination.Admin) },
                        onSettingsClick = {
                            navigateTo(DetailDestination.Settings)
                        },
                        onSignOut = {
                            scope.launch {
                                logger.info { "Signing out..." }
                                logoutUseCase()
                            }
                        },
                        onUserProfileClick = { userId ->
                            navigateTo(DetailDestination.UserProfile(userId))
                        },
                        homeContent = { padding, appHeader, onNavigateToLibrary ->
                            HomeScreen(
                                appHeader = appHeader,
                                onBookClick = { navigateTo(DetailDestination.Book(it)) },
                                onNavigateToLibrary = onNavigateToLibrary,
                                onShelfClick = { navigateTo(DetailDestination.Shelf(it)) },
                                onSeeAllShelves = onNavigateToLibrary,
                                modifier = Modifier.padding(padding),
                            )
                        },
                        libraryContent = { padding, appHeader ->
                            LibraryScreen(
                                onBookClick = { navigateTo(DetailDestination.Book(it)) },
                                onSeriesClick = { navigateTo(DetailDestination.Series(it)) },
                                onAuthorClick = { navigateTo(DetailDestination.Contributor(it)) },
                                onNarratorClick = { navigateTo(DetailDestination.Contributor(it)) },
                                appHeader = appHeader,
                                modifier = Modifier.padding(padding),
                            )
                        },
                        discoverContent = { padding, appHeader ->
                            DiscoverScreen(
                                appHeader = appHeader,
                                onShelfClick = { navigateTo(DetailDestination.Shelf(it)) },
                                onBookClick = { navigateTo(DetailDestination.Book(it)) },
                                onUserProfileClick = { navigateTo(DetailDestination.UserProfile(it)) },
                                modifier = Modifier.padding(padding),
                            )
                        },
                    )
                }
            }

            // Persistent mini player (visible when a book is loaded, hidden on NowPlaying screen)
            if (playerState !is NowPlayingState.Idle && !isShowingNowPlaying) {
                DesktopNowPlayingBar(
                    state = playerState,
                    progress = { playerProgressState.value },
                    onPlayPause = { nowPlayingViewModel.playPause() },
                    onSkipBack = { nowPlayingViewModel.skipBack() },
                    onSkipForward = { nowPlayingViewModel.skipForward() },
                    onClick = { navigateTo(DetailDestination.NowPlaying) },
                )
            }
        }
    }
}

/**
 * Renders the appropriate detail screen for the given destination.
 */
@Composable
private fun DetailScreen(
    destination: DetailDestination,
    navigateTo: (DetailDestination) -> Unit,
    navigateBack: () -> Unit,
    nowPlayingViewModel: NowPlayingViewModel,
) {
    when (destination) {
        is DetailDestination.Book -> {
            BookDetailScreen(
                bookId = destination.bookId,
                onBackClick = navigateBack,
                onEditClick = { navigateTo(DetailDestination.BookEdit(it)) },
                onMetadataSearchClick = { navigateTo(DetailDestination.MetadataSearch(it)) },
                onSeriesClick = { navigateTo(DetailDestination.Series(it)) },
                onContributorClick = { navigateTo(DetailDestination.Contributor(it)) },
                onGenreClick = { navigateTo(DetailDestination.Genre(it)) },
                onTagClick = { tagId, tagName -> navigateTo(DetailDestination.Tag(tagId, tagName)) },
                onMoodClick = { moodId, moodName -> navigateTo(DetailDestination.Mood(moodId, moodName)) },
                onUserProfileClick = { navigateTo(DetailDestination.UserProfile(it)) },
            )
        }

        is DetailDestination.BookEdit -> {
            BookEditScreen(
                bookId = destination.bookId,
                onBackClick = navigateBack,
                onSaveSuccess = navigateBack,
            )
        }

        is DetailDestination.Series -> {
            SeriesDetailScreen(
                seriesId = destination.seriesId,
                onBackClick = navigateBack,
                onBookClick = { navigateTo(DetailDestination.Book(it)) },
                onEditClick = { navigateTo(DetailDestination.SeriesEdit(it)) },
                onContributorClick = { navigateTo(DetailDestination.Contributor(it)) },
            )
        }

        is DetailDestination.SeriesEdit -> {
            SeriesEditScreen(
                seriesId = destination.seriesId,
                onBackClick = navigateBack,
                onSaveSuccess = navigateBack,
            )
        }

        is DetailDestination.Contributor -> {
            ContributorDetailScreen(
                contributorId = destination.contributorId,
                onBackClick = navigateBack,
                onBookClick = { navigateTo(DetailDestination.Book(it)) },
                onEditClick = { navigateTo(DetailDestination.ContributorEdit(it)) },
                onViewAllClick = { id, role -> navigateTo(DetailDestination.ContributorBooks(id, role)) },
                onMetadataClick = { navigateTo(DetailDestination.ContributorMetadataSearch(it)) },
            )
        }

        is DetailDestination.ContributorEdit -> {
            ContributorEditScreen(
                contributorId = destination.contributorId,
                onBackClick = navigateBack,
                onSaveSuccess = navigateBack,
            )
        }

        is DetailDestination.ContributorBooks -> {
            ContributorBooksScreen(
                contributorId = destination.contributorId,
                role = destination.role,
                onBackClick = navigateBack,
                onBookClick = { navigateTo(DetailDestination.Book(it)) },
            )
        }

        is DetailDestination.Shelf -> {
            ShelfDetailScreen(
                shelfId = destination.shelfId,
                onBack = navigateBack,
                onBookClick = { navigateTo(DetailDestination.Book(it)) },
                onEditClick = { navigateTo(DetailDestination.ShelfEdit(it)) },
            )
        }

        is DetailDestination.Tag -> {
            val viewModel: BrowseFacetViewModel = koinInject()
            FacetBooksScreen(
                kind = FacetKind.Tag,
                facetId = destination.tagId,
                facetName = destination.tagName,
                onBackClick = navigateBack,
                onBookClick = { navigateTo(DetailDestination.Book(it)) },
                viewModel = viewModel,
            )
        }

        is DetailDestination.Mood -> {
            val viewModel: BrowseFacetViewModel = koinInject()
            FacetBooksScreen(
                kind = FacetKind.Mood,
                facetId = destination.moodId,
                facetName = destination.moodName,
                onBackClick = navigateBack,
                onBookClick = { navigateTo(DetailDestination.Book(it)) },
                viewModel = viewModel,
            )
        }

        is DetailDestination.Genre -> {
            val viewModel: GenreDestinationViewModel = koinInject()
            GenreDestinationScreen(
                genreId = destination.genreId,
                onBackClick = navigateBack,
                onBookClick = { navigateTo(DetailDestination.Book(it)) },
                onGenreClick = { navigateTo(DetailDestination.Genre(it)) },
                viewModel = viewModel,
            )
        }

        is DetailDestination.ShelfEdit -> {
            CreateEditShelfScreen(
                shelfId = destination.shelfId,
                onBack = navigateBack,
            )
        }

        is DetailDestination.ShelfCreate -> {
            CreateEditShelfScreen(
                shelfId = null,
                onBack = navigateBack,
            )
        }

        is DetailDestination.ContributorMetadataSearch -> {
            ContributorMetadataSearchRoute(
                contributorId = destination.contributorId,
                onCandidateSelected = { asin, region ->
                    navigateTo(DetailDestination.ContributorMetadataPreview(destination.contributorId, asin, region))
                },
                onBack = navigateBack,
            )
        }

        is DetailDestination.ContributorMetadataPreview -> {
            ContributorMetadataPreviewRoute(
                contributorId = destination.contributorId,
                asin = destination.asin,
                region = destination.region,
                onApplySuccess = {
                    navigateBack()
                    navigateBack()
                },
                onChangeMatch = navigateBack,
                onBack = navigateBack,
            )
        }

        is DetailDestination.MetadataSearch -> {
            MetadataSearchRoute(
                bookId = destination.bookId,
                onResultSelected = { asin, region ->
                    navigateTo(DetailDestination.MatchPreview(destination.bookId, asin, region))
                },
                onBack = navigateBack,
            )
        }

        is DetailDestination.MatchPreview -> {
            MatchPreviewRoute(
                bookId = destination.bookId,
                asin = destination.asin,
                region = destination.region,
                onBack = navigateBack,
                onApplySuccess = {
                    // Pop both MatchPreview and MetadataSearch to go back to BookDetail
                    navigateBack()
                    navigateBack()
                },
            )
        }

        is DetailDestination.Settings -> {
            SettingsScreen(
                onNavigateBack = navigateBack,
                showSleepTimer = false,
                onNavigateToStorage = { navigateTo(DetailDestination.Storage) },
                onNavigateToLicenses = { navigateTo(DetailDestination.Licenses) },
            )
        }

        is DetailDestination.Licenses -> {
            LicensesScreen(
                onNavigateBack = navigateBack,
                onLicenseClick = { navigateTo(DetailDestination.LicenseDetail(it)) },
            )
        }

        is DetailDestination.LicenseDetail -> {
            LicenseDetailScreen(
                uniqueId = destination.uniqueId,
                onNavigateBack = navigateBack,
            )
        }

        is DetailDestination.Storage -> {
            com.calypsan.listenup.client.features.settings.StorageScreen(
                onNavigateBack = navigateBack,
            )
        }

        is DetailDestination.NowPlaying -> {
            val screenState by nowPlayingViewModel.screenState.collectAsStateWithLifecycle()
            val state = screenState.state
            val progressState = nowPlayingViewModel.progress.collectAsStateWithLifecycle()
            // "Go to Book" only meaningful when there's a known bookId — Active
            // has one; Error sometimes does; Idle never.
            val activeBookId: String? =
                when (state) {
                    is NowPlayingState.Active -> state.bookId
                    is NowPlayingState.Error -> state.bookId
                    is NowPlayingState.Idle -> null
                }
            DesktopNowPlayingScreen(
                state = state,
                progress = { progressState.value },
                onPlayPause = { nowPlayingViewModel.playPause() },
                onSkipBack = { nowPlayingViewModel.skipBack() },
                onSkipForward = { nowPlayingViewModel.skipForward() },
                onPreviousChapter = { nowPlayingViewModel.previousChapter() },
                onNextChapter = { nowPlayingViewModel.nextChapter() },
                onSeekWithinChapter = { progress -> nowPlayingViewModel.seekWithinChapter(progress) },
                onSetSpeed = { speed -> nowPlayingViewModel.setSpeed(speed) },
                onClose = {
                    nowPlayingViewModel.closeBook()
                    navigateBack()
                },
                onBackClick = navigateBack,
                onGoToBook =
                    activeBookId?.let { id ->
                        {
                            navigateBack()
                            navigateTo(DetailDestination.Book(id))
                        }
                    },
                onGoToSeries = { seriesId ->
                    navigateBack()
                    navigateTo(DetailDestination.Series(seriesId))
                },
                onGoToContributor = { contributorId ->
                    navigateBack()
                    navigateTo(DetailDestination.Contributor(contributorId))
                },
            )
        }

        is DetailDestination.Admin -> {
            val viewModel: AdminViewModel = koinInject()
            val settingsViewModel: AdminSettingsViewModel = koinInject()
            val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
            val readySettings = settingsState as? AdminSettingsUiState.Ready

            AdminScreen(
                viewModel = viewModel,
                onBackClick = navigateBack,
                onInviteClick = { navigateTo(DetailDestination.CreateInvite) },
                onCollectionsClick = { navigateTo(DetailDestination.AdminCollections) },
                onCategoriesClick = { navigateTo(DetailDestination.AdminCategories) },
                onInboxClick = { navigateTo(DetailDestination.AdminInbox) },
                onUserClick = { navigateTo(DetailDestination.UserDetail(it)) },
                serverName = readySettings?.serverName ?: "",
                onServerNameChange = { settingsViewModel.setServerName(it) },
                remoteUrl = readySettings?.remoteUrl ?: "",
                onRemoteUrlChange = { settingsViewModel.setRemoteUrl(it) },
                isDirty = readySettings?.isDirty == true,
                onSave = { settingsViewModel.saveAll() },
                settingsError =
                    (
                        readySettings?.error
                            ?: (settingsState as? AdminSettingsUiState.Error)?.error
                    )?.localized(),
                onClearSettingsError = { settingsViewModel.clearError() },
            )
        }

        is DetailDestination.CreateInvite -> {
            val viewModel: CreateInviteViewModel = koinInject()
            CreateInviteScreen(
                viewModel = viewModel,
                onBackClick = navigateBack,
                onSuccess = navigateBack,
            )
        }

        is DetailDestination.UserDetail -> {
            val viewModel: UserDetailViewModel = koinInject { parametersOf(destination.userId) }
            UserDetailScreen(
                viewModel = viewModel,
                onBackClick = navigateBack,
            )
        }

        is DetailDestination.AdminCollections -> {
            val viewModel: AdminCollectionsViewModel = koinInject()
            AdminCollectionsScreen(
                viewModel = viewModel,
                onBackClick = navigateBack,
                onCollectionClick = { navigateTo(DetailDestination.AdminCollectionDetail(it)) },
            )
        }

        is DetailDestination.AdminCollectionDetail -> {
            val viewModel: AdminCollectionDetailViewModel = koinInject { parametersOf(destination.collectionId) }
            AdminCollectionDetailScreen(
                viewModel = viewModel,
                onBackClick = navigateBack,
            )
        }

        is DetailDestination.AdminCategories -> {
            val viewModel: AdminCategoriesViewModel = koinInject()
            AdminCategoriesScreen(
                viewModel = viewModel,
                onBackClick = navigateBack,
            )
        }

        is DetailDestination.AdminInbox -> {
            val viewModel: AdminInboxViewModel = koinInject()
            AdminInboxScreen(
                viewModel = viewModel,
                onBackClick = navigateBack,
                // Tapping a row opens book-edit to fix tags/collections before release.
                onBookClick = { navigateTo(DetailDestination.BookEdit(it)) },
                // Per-row "Match on Audible" — opens the metadata match wizard for that book (iOS parity).
                onMatchClick = { navigateTo(DetailDestination.MetadataSearch(it)) },
            )
        }

        is DetailDestination.UserProfile -> {
            UserProfileScreen(
                userId = destination.userId,
                onBack = navigateBack,
                onEditClick = { /* Edit profile not implemented on desktop */ },
                onBookClick = { navigateTo(DetailDestination.Book(it)) },
                onShelfClick = { navigateTo(DetailDestination.Shelf(it)) },
                onCreateShelfClick = { navigateTo(DetailDestination.ShelfCreate) },
            )
        }
    }
}
