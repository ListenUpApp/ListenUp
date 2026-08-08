package com.calypsan.listenup.client.di

import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Browser Koin initialization. Structurally identical to the JVM actual; the modules it starts
 * are the stubs below.
 */
internal actual fun initializeKoin(additionalModules: List<Module>) {
    startKoin {
        modules(sharedModules + additionalModules)
    }
}

/**
 * Browser discovery module.
 *
 * Empty permanently, not pending: mDNS does not exist in a browser and never will. A web client
 * reaches its server by URL — which is the "Never Stranded" manual fallback the native clients
 * already carry behind discovery.
 */
internal actual val platformDiscoveryModule: Module = module { }

/**
 * Browser storage module.
 *
 * Empty by design (web seam check). A real binding needs SecureStorage, StoragePaths,
 * ImageStorage, NetworkMonitor and DownloadFileManager, each of which needs a browser decision —
 * credential storage in particular is not a direct port of a native keystore — so none is
 * guessed at here.
 */
internal actual val platformStorageModule: Module = module { }

/**
 * Browser device module.
 *
 * Empty by design (web seam check): it would bind
 * [com.calypsan.listenup.client.device.DeviceContextProvider], which cannot yet classify a
 * browser.
 */
internal actual val platformDeviceModule: Module = module { }
