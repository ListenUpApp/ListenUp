package com.calypsan.listenup.client.di

import com.calypsan.listenup.core.JvmSecureStorage
import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.client.data.discovery.JmDnsDiscoveryService
import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import com.calypsan.listenup.client.data.local.images.CommonImageStorage
import com.calypsan.listenup.client.data.local.images.CoverColorExtractor
import com.calypsan.listenup.client.data.local.images.JvmCoverColorExtractor
import com.calypsan.listenup.client.data.local.images.JvmStoragePaths
import com.calypsan.listenup.client.data.local.images.StoragePaths
import com.calypsan.listenup.client.data.repository.JvmNetworkMonitor
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.download.DownloadFileManager
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * JVM desktop Koin initialization.
 *
 * Starts Koin with all shared modules plus any additional desktop-specific modules.
 * Called from the desktop application's main entry point.
 *
 * @param additionalModules Platform-specific modules to include (e.g., playback, navigation)
 */
actual fun initializeKoin(additionalModules: List<Module>) {
    startKoin {
        modules(sharedModules + additionalModules)
    }
}

/**
 * JVM desktop has no default base URL.
 * Users must configure server URL manually or via discovery.
 *
 * Returns a placeholder that will be replaced when user configures the server.
 */
actual fun getBaseUrl(): String = "http://localhost:8080"

/**
 * JVM desktop discovery module.
 *
 * Uses JmDNS for mDNS/Zeroconf server discovery on the local network.
 */
actual val platformDiscoveryModule: Module =
    module {
        single { JmDnsDiscoveryService() } bind ServerDiscoveryService::class
    }

/**
 * JVM desktop storage module.
 *
 * Provides:
 * - SecureStorage: Encrypted file-based credential storage
 * - StoragePaths: Platform-appropriate app data directories
 * - ImageStorage: Common image storage using JVM paths
 * - NetworkMonitor: Health-check based connectivity detection
 * - CoverColorExtractor: AWT-based palette extraction
 * - DownloadFileManager: Audiobook file management
 */
actual val platformStorageModule: Module =
    module {
        single<JvmStoragePaths> { JvmStoragePaths() }

        single<StoragePaths> { get<JvmStoragePaths>() }

        single<SecureStorage> {
            val storagePaths: JvmStoragePaths = get()
            JvmSecureStorage(storagePaths.getSecureStoragePath())
        }

        single<ImageStorage> {
            CommonImageStorage(get())
        }

        single<NetworkMonitor> {
            val secureStorage: SecureStorage = get()
            JvmNetworkMonitor(
                serverUrlProvider = {
                    // Read server URL from secure storage (same key as SettingsRepository)
                    kotlinx.coroutines.runBlocking {
                        secureStorage.read("server_url")
                    }
                },
            )
        }

        single<CoverColorExtractor> {
            JvmCoverColorExtractor()
        }

        single {
            DownloadFileManager(storagePaths = get())
        }
    }

/**
 * JVM/Desktop device detection module.
 * Always returns Desktop type.
 */
actual val platformDeviceModule: Module =
    module {
        single {
            com.calypsan.listenup.client.device
                .DeviceContextProvider()
        }
        single { get<com.calypsan.listenup.client.device.DeviceContextProvider>().detect() }
    }
