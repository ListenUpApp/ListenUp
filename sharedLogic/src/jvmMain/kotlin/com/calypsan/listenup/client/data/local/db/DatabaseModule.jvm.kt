package com.calypsan.listenup.client.data.local.db

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.images.JvmStoragePaths
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_14_15
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_15_16
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_16_17
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
actual val platformDatabaseModule: Module =
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
                .addMigrations(MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)
                // No public installs yet — every schema change nukes and re-creates local
                // data. Flip back to `false` + a proper Migration chain before launch.
                .fallbackToDestructiveMigration(true)
                .build()
        }
    }

/**
 * Room callback that ensures FTS5 tables exist.
 *
 * FTS tables cannot be declared as Room entities, so they are created via callback.
 * Using onOpen ensures tables exist on both fresh installs and upgrades.
 */
private class FtsTableCallback : RoomDatabase.Callback() {
    override fun onOpen(connection: SQLiteConnection) {
        super.onOpen(connection)

        connection.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS books_fts USING fts5(
                bookId,
                title,
                subtitle,
                description,
                author,
                narrator,
                seriesName,
                genres,
                tokenize='porter'
            )
            """.trimIndent(),
        )

        connection.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS contributors_fts USING fts5(
                contributorId,
                name,
                description,
                tokenize='porter'
            )
            """.trimIndent(),
        )

        connection.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS series_fts USING fts5(
                seriesId,
                name,
                description,
                tokenize='porter'
            )
            """.trimIndent(),
        )
    }
}
