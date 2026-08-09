package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.discovery.NoDiscoveryService
import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import com.calypsan.listenup.client.data.local.documents.BrowserDocumentStorage
import com.calypsan.listenup.client.data.local.documents.DocumentStorage
import com.calypsan.listenup.client.data.local.images.BrowserImageStorage
import com.calypsan.listenup.client.data.repository.BrowserNetworkMonitor
import com.calypsan.listenup.client.device.DeviceContextProvider
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.download.DownloadFileManager
import com.calypsan.listenup.core.BrowserSecureStorage
import com.calypsan.listenup.core.CachingSecureStorage
import com.calypsan.listenup.core.SecureStorage
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Browser Koin initialization. Structurally identical to the JVM actual; the graph it starts
 * additionally needs a `Worker` binding for the SQLite web worker, which the consuming
 * application supplies through [additionalModules] — `:app:sharedLogic` ships no worker script.
 */
internal actual fun initializeKoin(additionalModules: List<Module>) {
    startKoin {
        modules(sharedModules + additionalModules)
    }
}

/**
 * Public browser accessor for the shared Koin modules, mirroring `jvmSharedModules()`: the
 * `List<Module>` type stays out of commonMain so it never reaches the iOS Swift Export surface,
 * and `:app:webApp` appends its platform modules (the worker) to this list in its own
 * `startKoin { … }`.
 */
fun jsSharedModules(): List<Module> = sharedModules

/**
 * Browser discovery module: binds [NoDiscoveryService] — mDNS does not exist in a browser and
 * never will, but the connection coordinator takes discovery as an input on every platform, so
 * the truthful binding is one that permanently finds nothing. A web client reaches its server
 * by URL — the "Never Stranded" manual fallback the native clients already carry.
 */
internal actual val platformDiscoveryModule: Module =
    module {
        single<ServerDiscoveryService> { NoDiscoveryService() }
    }

/**
 * Browser storage module. Each binding is a browser decision, documented on its class:
 * [BrowserSecureStorage] (origin-scoped localStorage, wrapped in the same read cache as the
 * JVM binding), [BrowserImageStorage] (no local byte cache — the server's blob endpoints plus
 * the browser's HTTP cache are the web cover story), [BrowserNetworkMonitor]
 * (`navigator.onLine`), and the deliberately-throwing [DownloadFileManager] (offline audio in
 * a browser is undesigned; the binding exists so the graph resolves, and any call is loud).
 */
internal actual val platformStorageModule: Module =
    module {
        single<SecureStorage> { CachingSecureStorage(BrowserSecureStorage()) }

        // Overrides mediaModule's filesystem DocumentStorage — platform modules load last for
        // exactly this. See BrowserDocumentStorage for why the common one cannot run here.
        single<DocumentStorage> { BrowserDocumentStorage() }

        single<ImageStorage> { BrowserImageStorage() }

        single<NetworkMonitor> { BrowserNetworkMonitor() }

        single { DownloadFileManager() }
    }

/**
 * Browser device module: pointer-capability + viewport classification, detected once at startup
 * like every other platform.
 */
internal actual val platformDeviceModule: Module =
    module {
        single { DeviceContextProvider() }
        single { get<DeviceContextProvider>().detect() }
    }
