package com.calypsan.listenup.client.di

import com.calypsan.listenup.core.CachingSecureStorage
import com.calypsan.listenup.core.JvmSecureStorage
import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.client.data.discovery.JmDnsDiscoveryService
import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import com.calypsan.listenup.client.data.local.images.CommonImageStorage
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
internal actual fun initializeKoin(additionalModules: List<Module>) {
    startKoin {
        modules(sharedModules + additionalModules)
    }
}

/**
 * Public JVM accessor for the shared Koin modules.
 *
 * Lives in `jvmMain` (not `commonMain`) so the `List<Module>` return type never reaches the iOS
 * Swift Export surface, where exposing Koin's `Module` type crashes the link. `:desktopApp` owns
 * its own `startKoin { … }` and appends its platform modules to this list.
 */
fun jvmSharedModules(): List<Module> = sharedModules

/**
 * Public JVM accessor for the shared playback presentation module.
 *
 * Lives in `jvmMain` (not `commonMain`) so the `Module` return type never reaches the iOS Swift
 * Export surface. `:desktopApp` appends this to its `startKoin { … }` module list.
 */
fun jvmPlaybackPresentationModule(): Module = playbackPresentationModule

/**
 * Public JVM accessor for the auth Koin module, for the server's end-to-end test fixture.
 *
 * Lives in `jvmMain` (not `commonMain`) so the `Module` return type never reaches the iOS Swift
 * Export surface. `:server`'s `AuthEndToEndFixture` wires this into its own `koinApplication { }`
 * scope with test overrides; it needs the module itself, not a full `startKoin`.
 */
fun clientAuthModuleForTests(): Module = clientAuthModule

/**
 * Public JVM Koin module that binds a real `ApiClientFactory` for the server's end-to-end fixture.
 *
 * The `ApiClientFactory` type (and `createApiClientFactory`) are `internal` to `:sharedLogic` so the
 * Ktor `HttpClient` stays off the Swift Export surface — meaning `:server`'s `AuthEndToEndFixture`
 * can no longer bind the type itself. This seam does the binding inside `:sharedLogic`, resolving
 * `ServerConfig` / `AuthSession` / `AuthRepository` from the same Koin scope, so the fixture just
 * includes this module. Its signature names no internal type.
 */
fun clientApiClientFactoryTestModule(): Module =
    module {
        single<com.calypsan.listenup.client.data.remote.ApiClientFactory> {
            com.calypsan.listenup.client.data.remote.createApiClientFactory(
                serverConfig = get(),
                authSession = get(),
                refreshAccessToken = {
                    get<com.calypsan.listenup.client.domain.repository.AuthRepository>().refreshAccessToken()
                },
                // Not resolved via `get()`: the minimal `AuthEndToEndFixture` DI scope deliberately
                // omits `appCoreModule` (which owns the `ClientIdentity` binding) — see its comment.
                // Same module (`:sharedLogic` jvmMain) can reference the internal object directly.
                clientIdentity = com.calypsan.listenup.client.domain.version.DefaultClientIdentity,
            )
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
internal actual val platformDiscoveryModule: Module =
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
 * - DownloadFileManager: Audiobook file management
 */
internal actual val platformStorageModule: Module =
    module {
        single<JvmStoragePaths> { JvmStoragePaths() }

        single<StoragePaths> { get<JvmStoragePaths>() }

        single<SecureStorage> {
            val storagePaths: JvmStoragePaths = get()
            CachingSecureStorage(JvmSecureStorage(storagePaths.getSecureStoragePath()))
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

        single {
            DownloadFileManager(storagePaths = get())
        }
    }

/**
 * JVM/Desktop device detection module.
 * Always returns Desktop type.
 */
internal actual val platformDeviceModule: Module =
    module {
        single {
            com.calypsan.listenup.client.device
                .DeviceContextProvider()
        }
        single { get<com.calypsan.listenup.client.device.DeviceContextProvider>().detect() }
    }
