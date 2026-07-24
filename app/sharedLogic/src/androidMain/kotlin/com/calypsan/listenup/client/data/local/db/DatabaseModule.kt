package com.calypsan.listenup.client.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_1_2
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_2_3
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android-specific database module.
 * Provides Room database configured for Android with proper file location.
 */
internal actual val platformDatabaseModule: Module =
    module {
        single {
            val context: Context = get()
            val dbFile = context.getDatabasePath("listenup.db")

            Room
                .databaseBuilder<ListenUpDatabase>(
                    context = context.applicationContext,
                    name = dbFile.absolutePath,
                ).setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addCallback(FtsTableCallback())
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                // Non-destructive: a schema mismatch throws (loudly) rather than SILENTLY wiping the
                // local DB — which includes the unsynced outbox (queued playback positions, listening
                // history, offline edits not yet pushed). Every schema-version bump MUST ship a Room
                // Migration that preserves data; re-adding fallbackToDestructiveMigration here
                // reintroduces silent data loss (MIG-1). See the ListenUpDatabase KDoc.
                .build()
        }
    }
