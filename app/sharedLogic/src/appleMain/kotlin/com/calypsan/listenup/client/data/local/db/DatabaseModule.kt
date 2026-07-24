package com.calypsan.listenup.client.data.local.db

import androidx.room3.Room
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * Apple database location: the app's Documents directory.
 *
 * NSDocumentDirectory rather than NSHomeDirectory, because the database lock file (`.lck`) needs
 * write access and does not get it under the home directory on real devices.
 *
 * Construction lives in [buildConfigured] — this module only resolves the path.
 */
internal actual val platformDatabaseModule: Module =
    module {
        single {
            val urls =
                NSFileManager.defaultManager.URLsForDirectory(
                    NSDocumentDirectory,
                    NSUserDomainMask,
                )

            @Suppress("UNCHECKED_CAST")
            val documentsUrl = (urls as List<NSURL>).first()

            Room
                .databaseBuilder<ListenUpDatabase>(name = documentsUrl.path + "/listenup.db")
                .buildConfigured()
        }
    }
