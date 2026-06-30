package com.calypsan.listenup.client.di

import com.calypsan.listenup.core.AppleSecureStorage
import com.calypsan.listenup.core.CachingSecureStorage
import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.client.data.local.images.CommonImageStorage
import com.calypsan.listenup.client.data.local.images.AppleStoragePaths
import com.calypsan.listenup.client.data.local.images.StoragePaths
import com.calypsan.listenup.client.data.repository.AppleNetworkMonitor
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Apple (iOS/macOS) storage module.
 * Provides SecureStorage implementation using Apple Keychain Services.
 */
internal actual val platformStorageModule: Module =
    module {
        single<SecureStorage> {
            CachingSecureStorage(AppleSecureStorage())
        }

        single<StoragePaths> {
            AppleStoragePaths()
        }

        single<ImageStorage> {
            CommonImageStorage(get())
        }

        single<NetworkMonitor> {
            AppleNetworkMonitor()
        }
    }
