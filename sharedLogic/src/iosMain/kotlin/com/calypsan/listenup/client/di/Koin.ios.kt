package com.calypsan.listenup.client.di

import com.calypsan.listenup.core.configureLogging
import com.calypsan.listenup.client.data.discovery.AppleDiscoveryService
import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import com.calypsan.listenup.client.domain.usecase.GetInstanceUseCase
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.mp.KoinPlatform
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
 * `Any`), and because it is **not** `inline`, the Koin call in its body is not copied into
 * callers' bodies. That keeps `org.koin.core.Koin` out of the Swift-exported closure of
 * [KoinHelper] — a reified `inline` resolver would inline `KoinPlatform.getKoin()` into every
 * exported accessor and re-leak the DI framework into the Swift Export bridge.
 */
private fun resolveDependency(type: kotlin.reflect.KClass<*>): Any =
    KoinPlatform.getKoin().get(type, null, null)

/**
 * Helper object for accessing dependencies from Swift.
 *
 * Provides strongly-typed accessors that are easier to use from Swift. Resolution goes through
 * the non-inline [resolveDependency] (not the `KoinComponent` interface, not a reified inline
 * helper) so that no Koin type appears on — or is inlined into — this object's public surface.
 * Every accessor returns a domain or presentation type, never a Koin type.
 */
object KoinHelper {
    fun getInstanceUseCase(): GetInstanceUseCase = resolveDependency(GetInstanceUseCase::class) as GetInstanceUseCase

    fun getServerConnectViewModel(): com.calypsan.listenup.client.presentation.connect.ServerConnectViewModel =
        resolveDependency(com.calypsan.listenup.client.presentation.connect.ServerConnectViewModel::class)
            as com.calypsan.listenup.client.presentation.connect.ServerConnectViewModel

    fun getLoginViewModel(): com.calypsan.listenup.client.presentation.auth.LoginViewModel =
        resolveDependency(com.calypsan.listenup.client.presentation.auth.LoginViewModel::class)
            as com.calypsan.listenup.client.presentation.auth.LoginViewModel

    fun getRegisterViewModel(): com.calypsan.listenup.client.presentation.auth.RegisterViewModel =
        resolveDependency(com.calypsan.listenup.client.presentation.auth.RegisterViewModel::class)
            as com.calypsan.listenup.client.presentation.auth.RegisterViewModel

    fun getServerSelectViewModel(): com.calypsan.listenup.client.presentation.connect.ServerSelectViewModel =
        resolveDependency(com.calypsan.listenup.client.presentation.connect.ServerSelectViewModel::class)
            as com.calypsan.listenup.client.presentation.connect.ServerSelectViewModel

    fun getAuthSession(): com.calypsan.listenup.client.domain.repository.AuthSession =
        resolveDependency(com.calypsan.listenup.client.domain.repository.AuthSession::class)
            as com.calypsan.listenup.client.domain.repository.AuthSession

    fun getServerConfig(): com.calypsan.listenup.client.domain.repository.ServerConfig =
        resolveDependency(com.calypsan.listenup.client.domain.repository.ServerConfig::class)
            as com.calypsan.listenup.client.domain.repository.ServerConfig

    fun getUserRepository(): com.calypsan.listenup.client.domain.repository.UserRepository =
        resolveDependency(com.calypsan.listenup.client.domain.repository.UserRepository::class)
            as com.calypsan.listenup.client.domain.repository.UserRepository

    fun getLibraryViewModel(): com.calypsan.listenup.client.presentation.library.LibraryViewModel =
        resolveDependency(com.calypsan.listenup.client.presentation.library.LibraryViewModel::class)
            as com.calypsan.listenup.client.presentation.library.LibraryViewModel

    fun getBookDetailViewModel(): com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel =
        resolveDependency(com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel::class)
            as com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel

    fun getSeriesDetailViewModel(): com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailViewModel =
        resolveDependency(com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailViewModel::class)
            as com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailViewModel

    fun getContributorDetailViewModel(): ContributorDetailViewModel =
        resolveDependency(ContributorDetailViewModel::class) as ContributorDetailViewModel

    fun getProgressTracker(): ProgressTracker = resolveDependency(ProgressTracker::class) as ProgressTracker

    fun getBookRepository(): BookRepository = resolveDependency(BookRepository::class) as BookRepository

    fun getImageStorage(): ImageStorage = resolveDependency(ImageStorage::class) as ImageStorage

    fun getDownloadService(): DownloadService = resolveDependency(DownloadService::class) as DownloadService

    fun getSleepTimerManager(): SleepTimerManager = resolveDependency(SleepTimerManager::class) as SleepTimerManager

    fun getPlaybackPreparer(): PlaybackPreparer = resolveDependency(PlaybackPreparer::class) as PlaybackPreparer
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
