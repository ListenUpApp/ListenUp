package com.calypsan.listenup.client.data.local.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.calypsan.listenup.client.data.local.images.JvmStoragePaths
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_1_2
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
                .addMigrations(MIGRATION_1_2)
                // Non-destructive: a schema mismatch throws (loudly) rather than SILENTLY wiping the
                // local DB — which includes the unsynced outbox (queued playback positions, listening
                // history, offline edits not yet pushed). Every schema-version bump MUST ship a Room
                // Migration that preserves data; re-adding fallbackToDestructiveMigration here
                // reintroduces silent data loss (MIG-1). See the ListenUpDatabase KDoc.
                .build()
        }
    }
