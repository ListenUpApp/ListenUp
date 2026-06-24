package com.calypsan.listenup.client.di

import com.calypsan.listenup.core.configureLogging
import com.calypsan.listenup.client.data.discovery.AppleDiscoveryService
import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * macOS-specific Koin initialization.
 *
 * Starts Koin with shared modules plus any macOS-specific modules.
 * Also configures kotlin-logging.
 *
 * @param additionalModules macOS-specific modules to include
 */
internal actual fun initializeKoin(additionalModules: List<Module>) {
    // Configure logging before anything else
    configureLogging()

    startKoin {
        modules(sharedModules + macosPlaybackModule + additionalModules)
    }
}

/**
 * macOS has no default base URL.
 * Users must configure server URL manually or via discovery.
 */
actual fun getBaseUrl(): String = "http://localhost:8080"

/**
 * macOS discovery module.
 * Uses Bonjour-based mDNS discovery (same as iOS — NSNetServiceBrowser).
 */
internal actual val platformDiscoveryModule: Module =
    module {
        single { AppleDiscoveryService() } bind ServerDiscoveryService::class
    }

/**
 * macOS device detection module.
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
