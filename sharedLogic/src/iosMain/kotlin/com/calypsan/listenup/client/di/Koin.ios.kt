package com.calypsan.listenup.client.di

import com.calypsan.listenup.core.configureLogging
import com.calypsan.listenup.client.data.discovery.AppleDiscoveryService
import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import com.calypsan.listenup.client.domain.usecase.GetInstanceUseCase
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.playback.PlaybackPreparer
import com.calypsan.listenup.client.playback.PlaybackProgressReporter
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.presentation.admin.ABSImportHubViewModel
import com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailViewModel
import com.calypsan.listenup.client.presentation.admin.AdminCollectionsViewModel
import com.calypsan.listenup.client.presentation.admin.AdminInboxViewModel
import com.calypsan.listenup.client.presentation.admin.AdminSettingsViewModel
import com.calypsan.listenup.client.presentation.admin.AdminViewModel
import com.calypsan.listenup.client.presentation.admin.CreateInviteViewModel
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowViewModel
import com.calypsan.listenup.client.presentation.auth.LoginViewModel
import com.calypsan.listenup.client.presentation.auth.PendingApprovalViewModel
import com.calypsan.listenup.client.presentation.auth.RegisterViewModel
import com.calypsan.listenup.client.presentation.auth.SetupViewModel
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel
import com.calypsan.listenup.client.presentation.bookedit.BookEditViewModel
import com.calypsan.listenup.client.presentation.connect.ServerConnectViewModel
import com.calypsan.listenup.client.presentation.connect.ServerSelectViewModel
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditViewModel
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataViewModel
import com.calypsan.listenup.client.presentation.discover.ActivityFeedViewModel
import com.calypsan.listenup.client.presentation.discover.DiscoverViewModel
import com.calypsan.listenup.client.presentation.discover.LeaderboardViewModel
import com.calypsan.listenup.client.presentation.home.HomeStatsViewModel
import com.calypsan.listenup.client.presentation.home.HomeViewModel
import com.calypsan.listenup.client.presentation.invite.ClaimInviteViewModel
import com.calypsan.listenup.client.presentation.library.LibraryViewModel
import com.calypsan.listenup.client.presentation.metadata.MetadataViewModel
import com.calypsan.listenup.client.presentation.profile.EditProfileViewModel
import com.calypsan.listenup.client.presentation.profile.UserProfileViewModel
import com.calypsan.listenup.client.presentation.search.SearchViewModel
import com.calypsan.listenup.client.presentation.search.SeeAllSearchViewModel
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailViewModel
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditViewModel
import com.calypsan.listenup.client.presentation.settings.DevicesViewModel
import com.calypsan.listenup.client.presentation.settings.SettingsViewModel
import com.calypsan.listenup.client.presentation.setup.LibrarySetupViewModel
import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfViewModel
import com.calypsan.listenup.client.presentation.shelf.ShelfDetailViewModel
import com.calypsan.listenup.client.presentation.startup.AppStartupViewModel
import com.calypsan.listenup.client.presentation.tagdetail.TagDetailViewModel

/**
 * iOS-specific Koin initialization.
 *
 * Starts Koin with shared modules plus any iOS-specific modules.
 * Also configures kotlin-logging to use OSLog for unified logging.
 * Should be called from the iOS app's initialization code (typically in App struct).
 *
 * @param additionalModules iOS-specific modules to include
 */
internal actual fun initializeKoin(additionalModules: List<Module>) {
    // Configure logging before anything else
    configureLogging()

    startKoin {
        // Include shared modules, iOS playback module, and any app-specific modules
        modules(sharedModules + iosPlaybackModule + additionalModules)
    }
}

/**
 * iOS simulator connects to host via 127.0.0.1.
 * Using explicit IPv4 address instead of localhost to avoid IPv6 resolution issues.
 */
actual fun getBaseUrl(): String = "http://127.0.0.1:8080"

/**
 * iOS-specific discovery module.
 * Provides Bonjour-based mDNS discovery using NSNetServiceBrowser.
 */
internal actual val platformDiscoveryModule: Module =
    module {
        single { AppleDiscoveryService() } bind ServerDiscoveryService::class
    }

/**
 * Non-inline DI resolution by [KClass]. Its signature carries no Koin type (only `KClass` and
 * `Any`), and because it is **not** `inline`, the Koin call in its body is not copied into callers'
 * bodies. That keeps `org.koin.core.Koin` out of the Swift-exported closure of [KoinHelper] — a
 * reified `inline` resolver (the old `by inject()`) would inline `KoinPlatform.getKoin()` into every
 * exported accessor and re-leak the DI framework into the Swift Export bridge.
 */
private fun resolveDependency(type: kotlin.reflect.KClass<*>): Any = KoinPlatform.getKoin().get(type, null, null)

/**
 * Parameterized [resolveDependency]. Same non-inline rationale: the `parametersOf` closure stays in
 * this private helper's body, never inlined onto an exported accessor.
 */
private fun resolveDependencyWithParams(
    type: kotlin.reflect.KClass<*>,
    params: List<Any?>,
): Any = KoinPlatform.getKoin().get(type, null) { parametersOf(*params.toTypedArray()) }

/**
 * Helper object for accessing dependencies from Swift.
 *
 * Provides strongly-typed accessors that are easier to use from Swift. Resolution goes through the
 * non-inline [resolveDependency] / [resolveDependencyWithParams] (not the `KoinComponent` interface,
 * not a reified inline helper) so that no Koin type appears on — or is inlined into — this object's
 * public surface. Every accessor returns a domain or presentation type, never a Koin type.
 */
object KoinHelper {
    fun getInstanceUseCase(): GetInstanceUseCase = resolveDependency(GetInstanceUseCase::class) as GetInstanceUseCase

    fun getServerConnectViewModel(): ServerConnectViewModel =
        resolveDependency(ServerConnectViewModel::class) as ServerConnectViewModel

    fun getLoginViewModel(): LoginViewModel = resolveDependency(LoginViewModel::class) as LoginViewModel

    fun getRegisterViewModel(): RegisterViewModel = resolveDependency(RegisterViewModel::class) as RegisterViewModel

    fun getSetupViewModel(): SetupViewModel = resolveDependency(SetupViewModel::class) as SetupViewModel

    fun getClaimInviteViewModel(): ClaimInviteViewModel =
        resolveDependency(ClaimInviteViewModel::class) as ClaimInviteViewModel

    fun getPendingApprovalViewModel(
        userId: String,
        email: String,
    ): PendingApprovalViewModel =
        resolveDependencyWithParams(PendingApprovalViewModel::class, listOf(userId, email)) as PendingApprovalViewModel

    fun getServerSelectViewModel(): ServerSelectViewModel =
        resolveDependency(ServerSelectViewModel::class) as ServerSelectViewModel

    fun getLibrarySetupViewModel(): LibrarySetupViewModel =
        resolveDependency(LibrarySetupViewModel::class) as LibrarySetupViewModel

    fun getAppStartupViewModel(): AppStartupViewModel =
        resolveDependency(AppStartupViewModel::class) as AppStartupViewModel

    fun getAuthSession(): AuthSession = resolveDependency(AuthSession::class) as AuthSession

    fun getServerConfig(): ServerConfig = resolveDependency(ServerConfig::class) as ServerConfig

    /** The current access token as a plain String for Swift (SKIE unboxes the value class). */
    suspend fun accessToken(): String? = getAuthSession().getAccessToken()?.value

    /** The active server URL as a plain String for Swift (SKIE unboxes the value class). */
    suspend fun activeServerUrl(): String? = getServerConfig().getActiveUrl()?.raw

    fun getUserRepository(): UserRepository = resolveDependency(UserRepository::class) as UserRepository

    fun getLibraryViewModel(): LibraryViewModel = resolveDependency(LibraryViewModel::class) as LibraryViewModel

    fun getSyncRepository(): SyncRepository = resolveDependency(SyncRepository::class) as SyncRepository

    fun getHomeViewModel(): HomeViewModel = resolveDependency(HomeViewModel::class) as HomeViewModel

    fun getHomeStatsViewModel(): HomeStatsViewModel = resolveDependency(HomeStatsViewModel::class) as HomeStatsViewModel

    fun getSearchViewModel(): SearchViewModel = resolveDependency(SearchViewModel::class) as SearchViewModel

    fun getDiscoverViewModel(): DiscoverViewModel = resolveDependency(DiscoverViewModel::class) as DiscoverViewModel

    fun getLeaderboardViewModel(): LeaderboardViewModel =
        resolveDependency(LeaderboardViewModel::class) as LeaderboardViewModel

    fun getActivityFeedViewModel(): ActivityFeedViewModel =
        resolveDependency(ActivityFeedViewModel::class) as ActivityFeedViewModel

    fun getSeeAllSearchViewModel(): SeeAllSearchViewModel =
        resolveDependency(SeeAllSearchViewModel::class) as SeeAllSearchViewModel

    fun getSettingsViewModel(): SettingsViewModel = resolveDependency(SettingsViewModel::class) as SettingsViewModel

    fun getDevicesViewModel(): DevicesViewModel = resolveDependency(DevicesViewModel::class) as DevicesViewModel

    fun getAdminViewModel(): AdminViewModel = resolveDependency(AdminViewModel::class) as AdminViewModel

    fun getAdminSettingsViewModel(): AdminSettingsViewModel =
        resolveDependency(AdminSettingsViewModel::class) as AdminSettingsViewModel

    fun getCreateInviteViewModel(): CreateInviteViewModel =
        resolveDependency(CreateInviteViewModel::class) as CreateInviteViewModel

    fun getABSImportHubViewModel(): ABSImportHubViewModel =
        resolveDependency(ABSImportHubViewModel::class) as ABSImportHubViewModel

    fun getImportFlowViewModel(): ImportFlowViewModel =
        resolveDependency(ImportFlowViewModel::class) as ImportFlowViewModel

    fun getAdminInboxViewModel(): AdminInboxViewModel =
        resolveDependency(AdminInboxViewModel::class) as AdminInboxViewModel

    fun getAdminCollectionsViewModel(): AdminCollectionsViewModel =
        resolveDependency(AdminCollectionsViewModel::class) as AdminCollectionsViewModel

    fun getAdminCollectionDetailViewModel(collectionId: String): AdminCollectionDetailViewModel =
        resolveDependencyWithParams(
            AdminCollectionDetailViewModel::class,
            listOf(collectionId),
        ) as AdminCollectionDetailViewModel

    fun getBookDetailViewModel(): BookDetailViewModel =
        resolveDependency(BookDetailViewModel::class) as BookDetailViewModel

    fun getBookReadersViewModel(bookId: String): BookReadersViewModel =
        resolveDependencyWithParams(BookReadersViewModel::class, listOf(bookId)) as BookReadersViewModel

    fun getSeriesDetailViewModel(): SeriesDetailViewModel =
        resolveDependency(SeriesDetailViewModel::class) as SeriesDetailViewModel

    fun getContributorDetailViewModel(): ContributorDetailViewModel =
        resolveDependency(ContributorDetailViewModel::class) as ContributorDetailViewModel

    fun getTagDetailViewModel(): TagDetailViewModel = resolveDependency(TagDetailViewModel::class) as TagDetailViewModel

    fun getShelfDetailViewModel(): ShelfDetailViewModel =
        resolveDependency(ShelfDetailViewModel::class) as ShelfDetailViewModel

    fun getCreateEditShelfViewModel(): CreateEditShelfViewModel =
        resolveDependency(CreateEditShelfViewModel::class) as CreateEditShelfViewModel

    fun getSeriesEditViewModel(): SeriesEditViewModel =
        resolveDependency(SeriesEditViewModel::class) as SeriesEditViewModel

    fun getContributorEditViewModel(): ContributorEditViewModel =
        resolveDependency(ContributorEditViewModel::class) as ContributorEditViewModel

    fun getUserProfileViewModel(): UserProfileViewModel =
        resolveDependency(UserProfileViewModel::class) as UserProfileViewModel

    fun getEditProfileViewModel(): EditProfileViewModel =
        resolveDependency(EditProfileViewModel::class) as EditProfileViewModel

    fun getBookEditViewModel(): BookEditViewModel = resolveDependency(BookEditViewModel::class) as BookEditViewModel

    fun getMetadataViewModel(): MetadataViewModel = resolveDependency(MetadataViewModel::class) as MetadataViewModel

    fun getContributorMetadataViewModel(): ContributorMetadataViewModel =
        resolveDependency(ContributorMetadataViewModel::class) as ContributorMetadataViewModel

    fun getPlaybackProgressReporter(): PlaybackProgressReporter =
        resolveDependency(PlaybackProgressReporter::class) as PlaybackProgressReporter

    fun getBookRepository(): BookRepository = resolveDependency(BookRepository::class) as BookRepository

    fun getDocumentRepository(): DocumentRepository = resolveDependency(DocumentRepository::class) as DocumentRepository

    fun getImageStorage(): ImageStorage = resolveDependency(ImageStorage::class) as ImageStorage

    fun getImageRepository(): ImageRepository = resolveDependency(ImageRepository::class) as ImageRepository

    /**
     * String-keyed [ImageRepository.ensureBookCoverCached] for the Swift boundary, where the
     * [BookId] value class isn't constructible. Mirrors the existing String-based book-id surface
     * the iOS app uses everywhere (`BookListItem.idString`).
     */
    fun ensureBookCoverCached(bookId: String) {
        getImageRepository().ensureBookCoverCached(BookId(bookId))
    }

    fun getDownloadService(): DownloadService = resolveDependency(DownloadService::class) as DownloadService

    fun getSleepTimerManager(): SleepTimerManager = resolveDependency(SleepTimerManager::class) as SleepTimerManager

    fun getPlaybackPreparer(): PlaybackPreparer = resolveDependency(PlaybackPreparer::class) as PlaybackPreparer

    fun getPlaybackPreferences(): PlaybackPreferences =
        resolveDependency(PlaybackPreferences::class) as PlaybackPreferences
}

/**
 * iOS-specific device detection module.
 * Uses UIDevice.userInterfaceIdiom to detect device type.
 */
internal actual val platformDeviceModule: Module =
    module {
        single {
            com.calypsan.listenup.client.device
                .DeviceContextProvider()
        }
        single { get<com.calypsan.listenup.client.device.DeviceContextProvider>().detect() }
    }
