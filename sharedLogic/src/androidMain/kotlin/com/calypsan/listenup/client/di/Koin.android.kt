package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.discovery.NsdDiscoveryService
import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Android-specific Koin initialization.
 *
 * On Android, Koin is initialized in the Application class
 * where we have access to the Android Context.
 *
 * This function is a no-op on Android.
 */
actual fun initializeKoin(additionalModules: List<Module>) {
    // Android initialization happens in the Application class
    // See: composeApp/src/androidMain/kotlin/.../ListenUpApp.kt
}

/**
 * Android emulator uses 10.0.2.2 to connect to host's localhost.
 */
actual fun getBaseUrl(): String = "http://10.0.2.2:8080"

/**
 * Android-specific discovery module.
 * Provides NsdManager-based mDNS discovery.
 */
actual val platformDiscoveryModule: Module =
    module {
        single { NsdDiscoveryService(context = get()) } bind ServerDiscoveryService::class
    }

/**
 * Android-specific device detection module.
 * Uses UiModeManager and screen metrics to detect device type.
 */
actual val platformDeviceModule: Module =
    module {
        single {
            com.calypsan.listenup.client.device
                .DeviceContextProvider(context = get())
        }
        single { get<com.calypsan.listenup.client.device.DeviceContextProvider>().detect() }
    }
