package com.calypsan.listenup.client.data.local.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_1_2
import com.calypsan.listenup.core.IODispatcher
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
 * Room queries run on [IODispatcher], the single canonical background dispatcher — it resolves to
 * the real elastic IO pool on every platform (including Native).
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
                .setQueryCoroutineContext(IODispatcher)
                // Without this the FTS5 search tables are never created on Apple platforms,
                // so every search and FTS rebuild fails with `no such table: books_fts`.
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
