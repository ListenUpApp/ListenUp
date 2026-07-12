
package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.platformDatabaseModule
import org.koin.core.module.Module

/**
 * Platform-specific storage module.
 * Each platform provides SecureStorage implementation via this module.
 */
internal expect val platformStorageModule: Module

/**
 * Platform-specific discovery module.
 * Each platform provides mDNS/Bonjour discovery implementation.
 */
internal expect val platformDiscoveryModule: Module

/**
 * Platform-specific device detection module.
 * Each platform provides DeviceContextProvider implementation.
 */
internal expect val platformDeviceModule: Module

// networkModule is defined in NetworkModule.kt — relocated wholesale to avoid a
// top-level `val networkModule` name collision between this file and that file.

/**
 * Platform-specific base URL for the API.
 * - Android emulator: 10.0.2.2 (maps to host's localhost)
 * - iOS simulator: localhost/127.0.0.1
 * - Physical devices: Use your computer's LAN IP
 */
expect fun getBaseUrl(): String

/**
 * All shared modules that should be loaded in both Android and iOS.
 *
 * Internal because the list is `List<Module>` — exposing Koin's `Module` type on the public
 * surface drags the DI framework (and `ParametersHolder.initialize(MutableList<…>)`) into the
 * Swift Export bridge, which crashes the iOS link. JVM/Android entry points that need to
 * append platform modules reach it through the public accessors in the platform source sets
 * (`androidSharedModules()` in androidMain, `jvmSharedModules()` in jvmMain).
 */
internal val sharedModules =
    listOf(
        platformStorageModule,
        platformDatabaseModule,
        platformDiscoveryModule,
        platformDeviceModule,
        appCoreModule,
        settingsModule,
        networkModule,
        persistenceModule,
        connectionModule,
        bookModule,
        contributorModule,
        seriesModule,
        collectionModule,
        shelfModule,
        readingOrderModule,
        genreTagModule,
        searchModule,
        mediaModule,
        adminModule,
        socialModule,
        listeningModule,
        libraryModule,
        clientSyncModule,
        clientAuthModule,
        voiceModule,
        pushClientModule,
        campfireClientModule,
    ) + allPresentationModules

/**
 * Platform-specific initialization function.
 * Each platform (Android/iOS) implements this to set up Koin appropriately.
 *
 * Internal because its `List<Module>` signature exposes Koin's `Module` type. Keeping it off the
 * public surface keeps the DI framework out of the Swift Export bridge (where any `MutableList`
 * on the exported API double-emits a stdlib LLVM global and crashes the link). Swift callers use
 * [startDependencyInjection], which exposes no DI-framework types.
 *
 * @param additionalModules Platform-specific modules to include
 */
internal expect fun initializeKoin(additionalModules: List<Module> = emptyList())

/**
 * Public iOS/app entry point: starts dependency injection.
 *
 * Exposes no DI-framework types, so it is safe on the Swift Export surface. Swift's `ListenUpApp`
 * calls this in place of the now-internal [initializeKoin].
 */
fun startDependencyInjection() = initializeKoin()
