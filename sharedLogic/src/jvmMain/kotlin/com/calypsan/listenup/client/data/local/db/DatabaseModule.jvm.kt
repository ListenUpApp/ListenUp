package com.calypsan.listenup.client.data.local.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.calypsan.listenup.client.data.local.images.JvmStoragePaths
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
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * JVM desktop database module.
 * Provides Room database configured for desktop with proper file location.
 *
 * Database location: {appDataDir}/data/listenup.db
 * - Windows: %APPDATA%/ListenUp/data/listenup.db
 * - Linux: ~/.local/share/listenup/data/listenup.db
 */
internal actual val platformDatabaseModule: Module =
    module {
        single {
            val storagePaths: JvmStoragePaths = get()
            val dbPath = storagePaths.getDatabasePath()

            Room
                .databaseBuilder<ListenUpDatabase>(
                    name = dbPath,
                ).setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
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
                )
                // No public installs yet — every schema change nukes and re-creates local
                // data. Flip back to `false` + a proper Migration chain before launch.
                .fallbackToDestructiveMigration(true)
                .build()
        }
    }
