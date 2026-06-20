package com.calypsan.listenup.client.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_14_15
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_15_16
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_16_17
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_17_18
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_18_19
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_19_20
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_30_31
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_32_33
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_33_34
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
                )
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
