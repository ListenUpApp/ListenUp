package com.calypsan.listenup.client.data.local.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_14_15
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_15_16
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_16_17
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * Apple (iOS/macOS) database module.
 * Provides Room database configured for Apple platforms with proper file location in app Documents directory.
 *
 * Uses NSDocumentDirectory instead of NSHomeDirectory to ensure write access
 * for the database lock file (.lck) on real devices.
 *
 * Note: Uses Dispatchers.Default instead of Dispatchers.IO since IO is internal on Native platforms.
 */
actual val platformDatabaseModule: Module =
    module {
        single {
            val urls =
                NSFileManager.defaultManager.URLsForDirectory(
                    NSDocumentDirectory,
                    NSUserDomainMask,
                )

            @Suppress("UNCHECKED_CAST")
            val documentsUrl = (urls as List<NSURL>).first()
            val dbFile = documentsUrl.path + "/listenup.db"

            Room
                .databaseBuilder<ListenUpDatabase>(
                    name = dbFile,
                ).setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.Default)
                .addMigrations(MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)
                // No public installs yet — every schema change nukes and re-creates local
                // data. Flip back to `false` + a proper Migration chain before launch.
                .fallbackToDestructiveMigration(true)
                .build()
        }
    }
