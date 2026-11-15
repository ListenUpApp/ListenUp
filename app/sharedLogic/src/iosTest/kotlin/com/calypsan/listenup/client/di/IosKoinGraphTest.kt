package com.calypsan.listenup.client.di

import androidx.lifecycle.ViewModel
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.buildConfigured
import com.calypsan.listenup.client.domain.usecase.auth.LogoutUseCase
import com.calypsan.listenup.client.playback.PlaybackStateProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/**
 * Every ViewModel Swift asks [KoinHelper] for resolves from the graph the iOS app actually starts.
 *
 * The JVM `*ModuleVerifyTest` specs check each module against a list of types other modules supply, and the
 * JVM graph binds a `PlaybackManager`; iOS binds none. So a factory asking for one passed every JVM check and
 * aborted the iOS app the first time Swift opened that screen (Downloads, and the chapter editor). Only the
 * Apple lane compiles `iosMain`, so only here can the real [iosModules] be resolved.
 */
class IosKoinGraphTest :
    FunSpec({

        test("every ViewModel KoinHelper hands Swift resolves from the iOS graph") {
            startIosGraph()
            try {
                val unresolved =
                    swiftViewModelAccessors.mapNotNull { (name, accessor) ->
                        runCatching { accessor() }
                            .onSuccess(KoinHelper::closeViewModel)
                            .exceptionOrNull()
                            // Koin wraps the missing definition; the innermost cause names it.
                            ?.let { failure -> "$name: ${generateSequence(failure) { it.cause }.last().message}" }
                    }
                unresolved shouldBe emptyList()
            } finally {
                stopIosGraph()
            }
        }

        test("the iOS graph knows what is playing, for sign-out and Downloads' delete guard") {
            startIosGraph()
            try {
                val koin = KoinPlatform.getKoin()
                koin.get<PlaybackStateProvider>()
                koin.get<LogoutUseCase>()
            } finally {
                stopIosGraph()
            }
        }
    })

/** The production iOS graph, with only the database swapped for an in-memory one so no file is left behind. */
private fun startIosGraph() {
    startKoin {
        allowOverride(true)
        modules(
            iosModules +
                module {
                    single<ListenUpDatabase> {
                        Room.inMemoryDatabaseBuilder<ListenUpDatabase>().buildConfigured(BundledSQLiteDriver())
                    }
                },
        )
    }
}

private fun stopIosGraph() {
    val koin = KoinPlatform.getKoin()
    // Koin does not cancel the scopes it hands out; the player's and the playback scope run for the app's life.
    koin.get<CoroutineScope>(named(PLAYER_SCOPE)).cancel()
    koin.get<CoroutineScope>(named("playbackScope")).cancel()
    stopKoin()
}

private const val ID = "00000000-0000-0000-0000-000000000001"

/** Each `KoinHelper` ViewModel accessor, called exactly as Swift calls it. */
private val swiftViewModelAccessors: List<Pair<String, () -> ViewModel>> =
    with(KoinHelper) {
        listOf(
            "ServerConnect" to ::getServerConnectViewModel,
            "Login" to ::getLoginViewModel,
            "Register" to ::getRegisterViewModel,
            "Setup" to ::getSetupViewModel,
            "ClaimInvite" to ::getClaimInviteViewModel,
            "ForgotPassword" to ::getForgotPasswordViewModel,
            "PendingApproval" to { getPendingApprovalViewModel(userId = ID, email = "reader@example.com") },
            "ServerSelect" to ::getServerSelectViewModel,
            "LibrarySetup" to ::getLibrarySetupViewModel,
            "AppStartup" to ::getAppStartupViewModel,
            "ConnectionHealth" to ::getConnectionHealthViewModel,
            "Library" to ::getLibraryViewModel,
            "Home" to ::getHomeViewModel,
            "HomeStats" to ::getHomeStatsViewModel,
            "MyShelves" to ::getMyShelvesViewModel,
            "Search" to ::getSearchViewModel,
            "Discover" to ::getDiscoverViewModel,
            "Leaderboard" to ::getLeaderboardViewModel,
            "ActivityFeed" to ::getActivityFeedViewModel,
            "SeeAllSearch" to ::getSeeAllSearchViewModel,
            "Settings" to ::getSettingsViewModel,
            "Devices" to ::getDevicesViewModel,
            "HardcoverSettings" to ::getHardcoverSettingsViewModel,
            "KeptOffBooks" to ::getKeptOffBooksViewModel,
            "NotificationBell" to ::getNotificationBellViewModel,
            "Notifications" to ::getNotificationsViewModel,
            "NotificationPrefs" to ::getNotificationPrefsViewModel,
            "Admin" to ::getAdminViewModel,
            "AdminSettings" to ::getAdminSettingsViewModel,
            "LibrarySettings" to ::getLibrarySettingsViewModel,
            "CreateInvite" to ::getCreateInviteViewModel,
            "ABSImportHub" to ::getABSImportHubViewModel,
            "AdminBackup" to ::getAdminBackupViewModel,
            "OrganizeSettings" to ::getOrganizeSettingsViewModel,
            "UploadBooks" to ::getUploadBooksViewModel,
            "RestoreFromFile" to ::getRestoreFromFileViewModel,
            "RestoreBackup" to { getRestoreBackupViewModel(backupId = ID) },
            "ImportFlow" to ::getImportFlowViewModel,
            "AdminInbox" to ::getAdminInboxViewModel,
            "InboxBadge" to ::getInboxBadgeViewModel,
            "RestrictedBooks" to ::getRestrictedBooksViewModel,
            "AdminCollections" to ::getAdminCollectionsViewModel,
            "AdminCategories" to ::getAdminCategoriesViewModel,
            "AdminCollectionDetail" to { getAdminCollectionDetailViewModel(collectionId = ID) },
            "UserDetail" to { getUserDetailViewModel(userId = ID) },
            "UserPermissions" to { getUserPermissionsViewModel(userId = ID) },
            "BookDetail" to ::getBookDetailViewModel,
            "BookMultiSelect" to ::getBookMultiSelectViewModel,
            "BulkEdit" to { getBulkEditViewModel(bookIds = listOf(ID)) },
            "BookReaders" to { getBookReadersViewModel(bookId = ID) },
            "BookRatings" to { getBookRatingsViewModel(bookId = ID) },
            "HardcoverMatch" to { getHardcoverMatchViewModel(bookId = ID) },
            "BookHardcover" to { getBookHardcoverViewModel(bookId = ID) },
            "SeriesDetail" to ::getSeriesDetailViewModel,
            "ContributorDetail" to ::getContributorDetailViewModel,
            "ContributorBooks" to ::getContributorBooksViewModel,
            "Storage" to ::getStorageViewModel,
            "SyncIndicator" to ::getSyncIndicatorViewModel,
            "BrowseFacet" to ::getBrowseFacetViewModel,
            "GenreDestination" to ::getGenreDestinationViewModel,
            "ShelfDetail" to ::getShelfDetailViewModel,
            "CreateEditShelf" to ::getCreateEditShelfViewModel,
            "SeriesEdit" to ::getSeriesEditViewModel,
            "ChapterEditor" to { getChapterEditorViewModel(bookId = ID) },
            "ContributorEdit" to ::getContributorEditViewModel,
            "UserProfile" to ::getUserProfileViewModel,
            "EditProfile" to ::getEditProfileViewModel,
            "BookEdit" to ::getBookEditViewModel,
            "BookMatch" to { getBookMatchViewModel(bookId = ID) },
            "PersonMatch" to { getPersonMatchViewModel(contributorId = ID) },
            "MatchReceipt" to { getMatchReceiptViewModel(subjectId = ID) },
            "Player" to ::getPlayerViewModel,
        )
    }
