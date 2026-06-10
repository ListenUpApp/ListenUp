
package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.platformDatabaseModule
import org.koin.core.module.Module

/**
 * Platform-specific storage module.
 * Each platform provides SecureStorage implementation via this module.
 */
expect val platformStorageModule: Module

/**
 * Platform-specific discovery module.
 * Each platform provides mDNS/Bonjour discovery implementation.
 */
expect val platformDiscoveryModule: Module

/**
 * Platform-specific device detection module.
 * Each platform provides DeviceContextProvider implementation.
 */
expect val platformDeviceModule: Module

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
 */
val sharedModules =
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
        genreTagModule,
        searchModule,
        mediaModule,
        adminModule,
        socialModule,
        listeningModule,
        libraryModule,
        clientSyncRenovationModule,
        clientAuthModule,
        voiceModule,
    ) + allPresentationModules

/**
 * Platform-specific initialization function.
 * Each platform (Android/iOS) implements this to set up Koin appropriately.
 *
 * @param additionalModules Platform-specific modules to include
 */
expect fun initializeKoin(additionalModules: List<Module> = emptyList())
