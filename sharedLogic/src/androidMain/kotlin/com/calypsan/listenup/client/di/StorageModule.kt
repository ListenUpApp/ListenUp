package com.calypsan.listenup.client.di

import android.content.Context
import com.calypsan.listenup.core.AndroidSecureStorage
import com.calypsan.listenup.core.CachingSecureStorage
import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.client.data.local.images.AndroidStoragePaths
import com.calypsan.listenup.client.data.local.images.CommonImageStorage
import com.calypsan.listenup.client.data.local.images.StoragePaths
import com.calypsan.listenup.client.data.repository.AndroidNetworkMonitor
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android-specific storage module.
 * Provides SecureStorage implementation using Android Keystore.
 */
internal actual val platformStorageModule: Module =
    module {
        single<SecureStorage> {
            val context: Context = get()
            CachingSecureStorage(AndroidSecureStorage(context))
        }

        single<StoragePaths> {
            val context: Context = get()
            AndroidStoragePaths(context)
        }

        single<ImageStorage> {
            CommonImageStorage(get())
        }

        single<NetworkMonitor> {
            val context: Context = get()
            AndroidNetworkMonitor(context)
        }
    }
