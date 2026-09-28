package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.discovery.NsdDiscoveryService
import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import com.calypsan.listenup.client.playback.PlaybackControllerActivator
import org.koin.core.Koin
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
 * `Application` (in `:app:sharedUI`) owns its own `startKoin { androidContext(); … }` and appends its
 * platform modules to this list.
 */
fun androidSharedModules(): List<Module> = sharedModules

/**
 * Public Android accessor for the shared playback presentation module.
 *
 * Lives in `androidMain` (not `commonMain`) so the `Module` return type never reaches the iOS
 * Swift Export surface. The Android `Application` (in `:app:sharedUI`) appends this to its
 * `startKoin { … }` module list.
 */
fun androidPlaybackPresentationModule(): Module = playbackPresentationModule

/**
 * Connects this process to the playback service: the first call acquires the `PlaybackController`,
 * and every later call is a no-op, because all it does is resolve the process-lifetime
 * [PlaybackControllerActivator] single.
 *
 * Called by `MainActivity` and by `PlaybackService.onCreate`, so the in-app controller (and the
 * state it feeds `PlaybackManager`: playing, buffering, speed, errors, the auto-rewind ladder) is
 * live whenever there is a UI or a playback session, and never bound for a push or a worker wake.
 * Lives in `androidMain` for the same Swift Export reason as the accessors above.
 */
fun Koin.activatePlaybackController() {
    val _ = get<PlaybackControllerActivator>()
}

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
