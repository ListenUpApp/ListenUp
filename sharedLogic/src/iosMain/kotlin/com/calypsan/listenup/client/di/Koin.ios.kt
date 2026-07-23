package com.calypsan.listenup.client.di

import com.calypsan.listenup.core.configureLogging
import com.calypsan.listenup.client.data.discovery.AppleDiscoveryService
import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import com.calypsan.listenup.client.presentation.connection.ConnectionHealthViewModel
import com.calypsan.listenup.client.presentation.contributordetail.ContributorBooksViewModel
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailViewModel
import com.calypsan.listenup.client.presentation.storage.StorageViewModel
import com.calypsan.listenup.client.presentation.sync.SyncIndicatorViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.playback.PlaybackBandwidthCoordinator
import com.calypsan.listenup.client.playback.PlaybackPreparer
import com.calypsan.listenup.client.playback.PlaybackProgressReporter
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.playback.AudioTokenProvider
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.data.repository.DeepLinkManager
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.presentation.admin.ABSImportHubViewModel
import com.calypsan.listenup.client.presentation.admin.AdminBackupViewModel
import com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailViewModel
import com.calypsan.listenup.client.presentation.admin.UserDetailViewModel
import com.calypsan.listenup.client.presentation.admin.AdminCollectionsViewModel
import com.calypsan.listenup.client.presentation.admin.AdminInboxViewModel
import com.calypsan.listenup.client.presentation.admin.AdminSettingsViewModel
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsViewModel
import com.calypsan.listenup.client.presentation.admin.RestoreBackupViewModel
import com.calypsan.listenup.client.presentation.admin.RestoreFromFileViewModel
import com.calypsan.listenup.client.presentation.admin.AdminViewModel
import com.calypsan.listenup.client.presentation.admin.CreateInviteViewModel
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowViewModel
import com.calypsan.listenup.client.presentation.auth.LoginViewModel
import com.calypsan.listenup.client.presentation.auth.PendingApprovalViewModel
import com.calypsan.listenup.client.presentation.auth.RegisterViewModel
import com.calypsan.listenup.client.presentation.auth.SetupViewModel
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel
import com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetViewModel
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel
import com.calypsan.listenup.client.presentation.bookedit.BookEditViewModel
import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
import com.calypsan.listenup.client.presentation.connect.ServerConnectViewModel
import com.calypsan.listenup.client.presentation.connect.ServerSelectViewModel
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditViewModel
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataViewModel
import com.calypsan.listenup.client.presentation.discover.ActivityFeedViewModel
import com.calypsan.listenup.client.presentation.discover.DiscoverViewModel
import com.calypsan.listenup.client.presentation.discover.LeaderboardViewModel
import com.calypsan.listenup.client.presentation.genredestination.GenreDestinationViewModel
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
 * Typed wrapper over [resolveDependency] that does the unchecked cast once. Kept **non-inline** for
 * the same reason as [resolveDependency]: a `reified inline` resolver would inline `KoinPlatform.getKoin()`
 * into every exported accessor and re-leak `org.koin.core.Koin` onto the Swift Export surface. The
 * single cast here replaces the per-accessor `as` casts.
 */
private fun <T : Any> resolve(type: kotlin.reflect.KClass<T>): T {
    @Suppress("UNCHECKED_CAST")
    return resolveDependency(type) as T
}

/**
 * Parameterized [resolve]. Same non-inline rationale as [resolveDependencyWithParams].
 */
private fun <T : Any> resolveWithParams(
    type: kotlin.reflect.KClass<T>,
    params: List<Any?>,
): T {
    @Suppress("UNCHECKED_CAST")
    return resolveDependencyWithParams(type, params) as T
}

/**
 * Helper object for accessing dependencies from Swift.
 *
 * Provides strongly-typed accessors that are easier to use from Swift. Resolution goes through the
 * non-inline [resolveDependency] / [resolveDependencyWithParams] (not the `KoinComponent` interface,
 * not a reified inline helper) so that no Koin type appears on — or is inlined into — this object's
 * public surface. Every accessor returns a domain or presentation type, never a Koin type.
 */
object KoinHelper {
    fun getInstanceRepository(): InstanceRepository = resolve(InstanceRepository::class)

    fun getServerConnectViewModel(): ServerConnectViewModel = resolve(ServerConnectViewModel::class)

    fun getLoginViewModel(): LoginViewModel = resolve(LoginViewModel::class)

    fun getRegisterViewModel(): RegisterViewModel = resolve(RegisterViewModel::class)

    fun getSetupViewModel(): SetupViewModel = resolve(SetupViewModel::class)

    fun getClaimInviteViewModel(): ClaimInviteViewModel = resolve(ClaimInviteViewModel::class)

    fun getPendingApprovalViewModel(
        userId: String,
        email: String,
    ): PendingApprovalViewModel = resolveWithParams(PendingApprovalViewModel::class, listOf(userId, email))

    fun getServerSelectViewModel(): ServerSelectViewModel = resolve(ServerSelectViewModel::class)

    fun getLibrarySetupViewModel(): LibrarySetupViewModel = resolve(LibrarySetupViewModel::class)

    fun getAppStartupViewModel(): AppStartupViewModel = resolve(AppStartupViewModel::class)

    fun getAuthSession(): AuthSession = resolve(AuthSession::class)

    fun getConnectionHealthViewModel(): ConnectionHealthViewModel = resolve(ConnectionHealthViewModel::class)

    fun getServerConfig(): ServerConfig = resolve(ServerConfig::class)

    fun getDeepLinkManager(): DeepLinkManager = resolve(DeepLinkManager::class)

    /** The current access token as a plain String for Swift (SKIE unboxes the value class). */
    suspend fun accessToken(): String? = getAuthSession().getAccessToken()?.value

    /**
     * Access token for the Nuke image path, served from the shared single-flight cached token
     * authority — the SAME [AudioTokenProvider] the audio stream uses, the other raw-HTTP path that
     * bypasses the RPC channel's 401-heal. That provider keeps a proactively-refreshed token and
     * serves the last-known-good value even mid-refresh, so a burst of concurrent cover loads all
     * read one valid token instead of each racing its own refresh — the earlier per-request refresh
     * stampede returned no token for a fraction of requests (`token=MISSING`), which then 401'd and
     * left photos stale (the "iOS photo won't refresh in real time" bug). Primes once if the provider
     * is still cold (the first image right after launch); never triggers a per-request rotation.
     */
    suspend fun freshAccessToken(): String? {
        val provider = resolve(AudioTokenProvider::class)
        return provider.getToken() ?: run {
            provider.prepareForPlayback()
            provider.getToken()
        }
    }

    /** The active server URL as a plain String for Swift (SKIE unboxes the value class). */
    suspend fun activeServerUrl(): String? = getServerConfig().getActiveUrl()?.raw

    fun getUserRepository(): UserRepository = resolve(UserRepository::class)

    fun getLibraryViewModel(): LibraryViewModel = resolve(LibraryViewModel::class)

    fun getSyncRepository(): SyncRepository = resolve(SyncRepository::class)

    fun getHomeViewModel(): HomeViewModel = resolve(HomeViewModel::class)

    fun getHomeStatsViewModel(): HomeStatsViewModel = resolve(HomeStatsViewModel::class)

    fun getSearchViewModel(): SearchViewModel = resolve(SearchViewModel::class)

    fun getDiscoverViewModel(): DiscoverViewModel = resolve(DiscoverViewModel::class)

    fun getLeaderboardViewModel(): LeaderboardViewModel = resolve(LeaderboardViewModel::class)

    fun getActivityFeedViewModel(): ActivityFeedViewModel = resolve(ActivityFeedViewModel::class)

    fun getSeeAllSearchViewModel(): SeeAllSearchViewModel = resolve(SeeAllSearchViewModel::class)

    fun getSettingsViewModel(): SettingsViewModel = resolve(SettingsViewModel::class)

    fun getDevicesViewModel(): DevicesViewModel = resolve(DevicesViewModel::class)

    fun getAdminViewModel(): AdminViewModel = resolve(AdminViewModel::class)

    fun getAdminSettingsViewModel(): AdminSettingsViewModel = resolve(AdminSettingsViewModel::class)

    fun getLibrarySettingsViewModel(): LibrarySettingsViewModel = resolve(LibrarySettingsViewModel::class)

    fun getCreateInviteViewModel(): CreateInviteViewModel = resolve(CreateInviteViewModel::class)

    fun getABSImportHubViewModel(): ABSImportHubViewModel = resolve(ABSImportHubViewModel::class)

    fun getAdminBackupViewModel(): AdminBackupViewModel = resolve(AdminBackupViewModel::class)

    fun getRestoreFromFileViewModel(): RestoreFromFileViewModel = resolve(RestoreFromFileViewModel::class)

    fun getRestoreBackupViewModel(backupId: String): RestoreBackupViewModel =
        resolveWithParams(RestoreBackupViewModel::class, listOf(backupId))

    fun getImportFlowViewModel(): ImportFlowViewModel = resolve(ImportFlowViewModel::class)

    fun getAdminInboxViewModel(): AdminInboxViewModel = resolve(AdminInboxViewModel::class)

    fun getAdminCollectionsViewModel(): AdminCollectionsViewModel = resolve(AdminCollectionsViewModel::class)

    fun getAdminCollectionDetailViewModel(collectionId: String): AdminCollectionDetailViewModel =
        resolveWithParams(AdminCollectionDetailViewModel::class, listOf(collectionId))

    fun getUserDetailViewModel(userId: String): UserDetailViewModel =
        resolveWithParams(UserDetailViewModel::class, listOf(userId))

    fun getBookDetailViewModel(): BookDetailViewModel = resolve(BookDetailViewModel::class)

    fun getBookMultiSelectViewModel(): BookMultiSelectViewModel = resolve(BookMultiSelectViewModel::class)

    fun getBookReadersViewModel(bookId: String): BookReadersViewModel =
        resolveWithParams(BookReadersViewModel::class, listOf(bookId))

    fun getSeriesDetailViewModel(): SeriesDetailViewModel = resolve(SeriesDetailViewModel::class)

    fun getContributorDetailViewModel(): ContributorDetailViewModel = resolve(ContributorDetailViewModel::class)

    fun getContributorBooksViewModel(): ContributorBooksViewModel = resolve(ContributorBooksViewModel::class)

    fun getStorageViewModel(): StorageViewModel = resolve(StorageViewModel::class)

    fun getSyncIndicatorViewModel(): SyncIndicatorViewModel = resolve(SyncIndicatorViewModel::class)

    fun getBrowseFacetViewModel(): BrowseFacetViewModel = resolve(BrowseFacetViewModel::class)

    fun getGenreDestinationViewModel(): GenreDestinationViewModel = resolve(GenreDestinationViewModel::class)

    fun getShelfDetailViewModel(): ShelfDetailViewModel = resolve(ShelfDetailViewModel::class)

    fun getCreateEditShelfViewModel(): CreateEditShelfViewModel = resolve(CreateEditShelfViewModel::class)

    fun getSeriesEditViewModel(): SeriesEditViewModel = resolve(SeriesEditViewModel::class)

    fun getContributorEditViewModel(): ContributorEditViewModel = resolve(ContributorEditViewModel::class)

    fun getUserProfileViewModel(): UserProfileViewModel = resolve(UserProfileViewModel::class)

    fun getEditProfileViewModel(): EditProfileViewModel = resolve(EditProfileViewModel::class)

    fun getBookEditViewModel(): BookEditViewModel = resolve(BookEditViewModel::class)

    fun getMetadataViewModel(): MetadataViewModel = resolve(MetadataViewModel::class)

    fun getContributorMetadataViewModel(): ContributorMetadataViewModel = resolve(ContributorMetadataViewModel::class)

    fun getPlaybackProgressReporter(): PlaybackProgressReporter = resolve(PlaybackProgressReporter::class)

    fun getBookRepository(): BookRepository = resolve(BookRepository::class)

    fun getHomeRepository(): HomeRepository = resolve(HomeRepository::class)

    fun getDocumentRepository(): DocumentRepository = resolve(DocumentRepository::class)

    fun getImageStorage(): ImageStorage = resolve(ImageStorage::class)

    fun getImageRepository(): ImageRepository = resolve(ImageRepository::class)

    fun getUserProfileRepository(): UserProfileRepository = resolve(UserProfileRepository::class)

    /**
     * String-keyed [ImageRepository.ensureBookCoverCached] for the Swift boundary, where the
     * [BookId] value class isn't constructible. Mirrors the existing String-based book-id surface
     * the iOS app uses everywhere (`BookListItem.idString`).
     */
    fun ensureBookCoverCached(bookId: String) {
        getImageRepository().ensureBookCoverCached(BookId(bookId))
    }

    fun getDownloadService(): DownloadService = resolve(DownloadService::class)

    fun getSleepTimerManager(): SleepTimerManager = resolve(SleepTimerManager::class)

    fun getPlaybackPreparer(): PlaybackPreparer = resolve(PlaybackPreparer::class)

    fun getPlaybackBandwidthCoordinator(): PlaybackBandwidthCoordinator = resolve(PlaybackBandwidthCoordinator::class)

    fun getPlaybackPreferences(): PlaybackPreferences = resolve(PlaybackPreferences::class)
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
