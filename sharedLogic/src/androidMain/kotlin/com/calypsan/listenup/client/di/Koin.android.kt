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
internal actual fun initializeKoin(additionalModules: List<Module>) {
    // Android initialization happens in the Application class
    // See: composeApp/src/androidMain/kotlin/.../ListenUpApp.kt
}

/**
 * Public Android accessor for the shared Koin modules.
 *
 * Lives in `androidMain` (not `commonMain`) so the `List<Module>` return type never reaches the
 * iOS Swift Export surface — exposing Koin's `Module` type there crashes the link. The Android
 * `Application` (in `:sharedUI`) owns its own `startKoin { androidContext(); … }` and appends its
 * platform modules to this list.
 */
fun androidSharedModules(): List<Module> = sharedModules

/**
 * Public Android accessor for the shared playback presentation module.
 *
 * Lives in `androidMain` (not `commonMain`) so the `Module` return type never reaches the iOS
 * Swift Export surface. The Android `Application` (in `:sharedUI`) appends this to its
 * `startKoin { … }` module list.
 */
fun androidPlaybackPresentationModule(): Module = playbackPresentationModule

/**
 * Android-specific discovery module.
 * Provides NsdManager-based mDNS discovery.
 */
internal actual val platformDiscoveryModule: Module =
    module {
        single { NsdDiscoveryService(context = get()) } bind ServerDiscoveryService::class
    }

/**
 * Android-specific device detection module.
 * Uses UiModeManager and screen metrics to detect device type.
 */
internal actual val platformDeviceModule: Module =
    module {
        single {
            com.calypsan.listenup.client.device
                .DeviceContextProvider(context = get())
        }
        single { get<com.calypsan.listenup.client.device.DeviceContextProvider>().detect() }
    }
