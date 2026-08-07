package com.calypsan.listenup.client.data.local.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.calypsan.listenup.client.data.local.images.JvmStoragePaths
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Desktop database location: `{appDataDir}/data/listenup.db`.
 * - Windows: `%APPDATA%/ListenUp/data/listenup.db`
 * - Linux: `~/.local/share/listenup/data/listenup.db`
 *
 * Construction lives in [buildConfigured] — this module only resolves the path.
 */
internal actual val platformDatabaseModule: Module =
    module {
        single {
            val storagePaths: JvmStoragePaths = get()
            Room
                .databaseBuilder<ListenUpDatabase>(name = storagePaths.getDatabasePath())
                .buildConfigured(BundledSQLiteDriver())
        }
    }
