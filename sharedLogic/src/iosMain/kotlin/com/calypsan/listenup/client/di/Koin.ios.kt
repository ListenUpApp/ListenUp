package com.calypsan.listenup.client.di

import com.calypsan.listenup.core.configureLogging
import com.calypsan.listenup.client.data.discovery.AppleDiscoveryService
import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import com.calypsan.listenup.client.domain.usecase.GetInstanceUseCase
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailViewModel
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.playback.PlaybackPreparer
import com.calypsan.listenup.client.playback.ProgressTracker
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage

/**
 * iOS-specific Koin initialization.
 *
 * Starts Koin with shared modules plus any iOS-specific modules.
 * Also configures kotlin-logging to use OSLog for unified logging.
 * Should be called from the iOS app's initialization code (typically in App struct).
 *
 * @param additionalModules iOS-specific modules to include
 */
actual fun initializeKoin(additionalModules: List<Module>) {
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
actual val platformDiscoveryModule: Module =
    module {
        single { AppleDiscoveryService() } bind ServerDiscoveryService::class
    }

/**
 * Helper object for accessing Koin dependencies from Swift.
 * Provides strongly-typed accessors that are easier to use from Swift.
 */
object KoinHelper : KoinComponent {
    fun getInstanceUseCase(): GetInstanceUseCase {
        val useCase: GetInstanceUseCase by inject()
        return useCase
    }

    fun getServerConnectViewModel(): com.calypsan.listenup.client.presentation.connect.ServerConnectViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.connect.ServerConnectViewModel by inject()
        return viewModel
    }

    fun getLoginViewModel(): com.calypsan.listenup.client.presentation.auth.LoginViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.auth.LoginViewModel by inject()
        return viewModel
    }

    fun getRegisterViewModel(): com.calypsan.listenup.client.presentation.auth.RegisterViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.auth.RegisterViewModel by inject()
        return viewModel
    }

    fun getSetupViewModel(): com.calypsan.listenup.client.presentation.auth.SetupViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.auth.SetupViewModel by inject()
        return viewModel
    }

    fun getClaimInviteViewModel(): com.calypsan.listenup.client.presentation.invite.ClaimInviteViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.invite.ClaimInviteViewModel by inject()
        return viewModel
    }

    fun getPendingApprovalViewModel(
        userId: String,
        email: String,
    ): com.calypsan.listenup.client.presentation.auth.PendingApprovalViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.auth.PendingApprovalViewModel by inject(
            parameters = { org.koin.core.parameter.parametersOf(userId, email) },
        )
        return viewModel
    }

    fun getServerSelectViewModel(): com.calypsan.listenup.client.presentation.connect.ServerSelectViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.connect.ServerSelectViewModel by inject()
        return viewModel
    }

    fun getLibrarySetupViewModel(): com.calypsan.listenup.client.presentation.setup.LibrarySetupViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.setup.LibrarySetupViewModel by inject()
        return viewModel
    }

    fun getAppStartupViewModel(): com.calypsan.listenup.client.presentation.startup.AppStartupViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.startup.AppStartupViewModel by inject()
        return viewModel
    }

    fun getAuthSession(): com.calypsan.listenup.client.domain.repository.AuthSession {
        val authSession: com.calypsan.listenup.client.domain.repository.AuthSession by inject()
        return authSession
    }

    fun getServerConfig(): com.calypsan.listenup.client.domain.repository.ServerConfig {
        val serverConfig: com.calypsan.listenup.client.domain.repository.ServerConfig by inject()
        return serverConfig
    }

    /** The current access token as a plain String for Swift (SKIE unboxes the value class). */
    suspend fun accessToken(): String? = getAuthSession().getAccessToken()?.value

    /** The active server URL as a plain String for Swift (SKIE unboxes the value class). */
    suspend fun activeServerUrl(): String? = getServerConfig().getActiveUrl()?.raw

    fun getUserRepository(): com.calypsan.listenup.client.domain.repository.UserRepository {
        val userRepository: com.calypsan.listenup.client.domain.repository.UserRepository by inject()
        return userRepository
    }

    fun getLibraryViewModel(): com.calypsan.listenup.client.presentation.library.LibraryViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.library.LibraryViewModel by inject()
        return viewModel
    }

    fun getSyncRepository(): com.calypsan.listenup.client.domain.repository.SyncRepository {
        val syncRepository: com.calypsan.listenup.client.domain.repository.SyncRepository by inject()
        return syncRepository
    }

    fun getHomeViewModel(): com.calypsan.listenup.client.presentation.home.HomeViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.home.HomeViewModel by inject()
        return viewModel
    }

    fun getHomeStatsViewModel(): com.calypsan.listenup.client.presentation.home.HomeStatsViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.home.HomeStatsViewModel by inject()
        return viewModel
    }

    fun getSearchViewModel(): com.calypsan.listenup.client.presentation.search.SearchViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.search.SearchViewModel by inject()
        return viewModel
    }

    fun getDiscoverViewModel(): com.calypsan.listenup.client.presentation.discover.DiscoverViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.discover.DiscoverViewModel by inject()
        return viewModel
    }

    fun getLeaderboardViewModel(): com.calypsan.listenup.client.presentation.discover.LeaderboardViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.discover.LeaderboardViewModel by inject()
        return viewModel
    }

    fun getActivityFeedViewModel(): com.calypsan.listenup.client.presentation.discover.ActivityFeedViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.discover.ActivityFeedViewModel by inject()
        return viewModel
    }

    fun getSeeAllSearchViewModel(): com.calypsan.listenup.client.presentation.search.SeeAllSearchViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.search.SeeAllSearchViewModel by inject()
        return viewModel
    }

    fun getSettingsViewModel(): com.calypsan.listenup.client.presentation.settings.SettingsViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.settings.SettingsViewModel by inject()
        return viewModel
    }

    fun getDevicesViewModel(): com.calypsan.listenup.client.presentation.settings.DevicesViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.settings.DevicesViewModel by inject()
        return viewModel
    }

    fun getAdminViewModel(): com.calypsan.listenup.client.presentation.admin.AdminViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.admin.AdminViewModel by inject()
        return viewModel
    }

    fun getAdminSettingsViewModel(): com.calypsan.listenup.client.presentation.admin.AdminSettingsViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.admin.AdminSettingsViewModel by inject()
        return viewModel
    }

    fun getCreateInviteViewModel(): com.calypsan.listenup.client.presentation.admin.CreateInviteViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.admin.CreateInviteViewModel by inject()
        return viewModel
    }

    fun getABSImportHubViewModel(): com.calypsan.listenup.client.presentation.admin.ABSImportHubViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.admin.ABSImportHubViewModel by inject()
        return viewModel
    }

    fun getImportFlowViewModel(): com.calypsan.listenup.client.presentation.admin.import.ImportFlowViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.admin.import.ImportFlowViewModel by inject()
        return viewModel
    }

    fun getBookDetailViewModel(): com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel by inject()
        return viewModel
    }

    fun getSeriesDetailViewModel(): com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailViewModel by inject()
        return viewModel
    }

    fun getContributorDetailViewModel(): ContributorDetailViewModel {
        val viewModel: ContributorDetailViewModel by inject()
        return viewModel
    }

    fun getTagDetailViewModel(): com.calypsan.listenup.client.presentation.tagdetail.TagDetailViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.tagdetail.TagDetailViewModel by inject()
        return viewModel
    }

    fun getShelfDetailViewModel(): com.calypsan.listenup.client.presentation.shelf.ShelfDetailViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.shelf.ShelfDetailViewModel by inject()
        return viewModel
    }

    fun getSeriesEditViewModel(): com.calypsan.listenup.client.presentation.seriesedit.SeriesEditViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.seriesedit.SeriesEditViewModel by inject()
        return viewModel
    }

    fun getContributorEditViewModel(): com.calypsan.listenup.client.presentation.contributoredit.ContributorEditViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.contributoredit.ContributorEditViewModel by inject()
        return viewModel
    }

    fun getUserProfileViewModel(): com.calypsan.listenup.client.presentation.profile.UserProfileViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.profile.UserProfileViewModel by inject()
        return viewModel
    }

    fun getEditProfileViewModel(): com.calypsan.listenup.client.presentation.profile.EditProfileViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.profile.EditProfileViewModel by inject()
        return viewModel
    }

    fun getBookEditViewModel(): com.calypsan.listenup.client.presentation.bookedit.BookEditViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.bookedit.BookEditViewModel by inject()
        return viewModel
    }

    fun getMetadataViewModel(): com.calypsan.listenup.client.presentation.metadata.MetadataViewModel {
        val viewModel: com.calypsan.listenup.client.presentation.metadata.MetadataViewModel by inject()
        return viewModel
    }

    fun getProgressTracker(): ProgressTracker {
        val instance: ProgressTracker by inject()
        return instance
    }

    fun getBookRepository(): BookRepository {
        val instance: BookRepository by inject()
        return instance
    }

    fun getImageStorage(): ImageStorage {
        val instance: ImageStorage by inject()
        return instance
    }

    fun getDownloadService(): DownloadService {
        val instance: DownloadService by inject()
        return instance
    }

    fun getSleepTimerManager(): SleepTimerManager {
        val instance: SleepTimerManager by inject()
        return instance
    }

    fun getPlaybackPreparer(): PlaybackPreparer {
        val instance: PlaybackPreparer by inject()
        return instance
    }
}

/**
 * iOS-specific device detection module.
 * Uses UIDevice.userInterfaceIdiom to detect device type.
 */
actual val platformDeviceModule: Module =
    module {
        single {
            com.calypsan.listenup.client.device
                .DeviceContextProvider()
        }
        single { get<com.calypsan.listenup.client.device.DeviceContextProvider>().detect() }
    }
