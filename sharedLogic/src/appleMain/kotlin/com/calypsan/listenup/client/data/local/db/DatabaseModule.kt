package com.calypsan.listenup.client.data.local.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_14_15
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_15_16
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_16_17
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_17_18
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_18_19
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_19_20
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_30_31
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_32_33
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_33_34
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_34_35
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_35_36
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_36_37
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_37_38
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_38_39
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
            val dbFile = documentsUrl.path + "/listenup.db"

            Room
                .databaseBuilder<ListenUpDatabase>(
                    name = dbFile,
                ).setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.Default)
                // Without this the FTS5 search tables are never created on Apple platforms,
                // so every search and FTS rebuild fails with `no such table: books_fts`.
                .addCallback(FtsTableCallback())
                .addMigrations(
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_30_31,
                    MIGRATION_32_33,
                    MIGRATION_33_34,
                    MIGRATION_34_35,
                    MIGRATION_35_36,
                    MIGRATION_36_37,
                    MIGRATION_37_38,
                    MIGRATION_38_39,
                )
                // No public installs yet — every schema change nukes and re-creates local
                // data. Flip back to `false` + a proper Migration chain before launch.
                .fallbackToDestructiveMigration(true)
                .build()
        }
    }
