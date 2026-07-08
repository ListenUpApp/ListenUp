package com.calypsan.listenup.client.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
                // No public installs yet — every schema change nukes and re-creates local
                // data. Flip back to `false` + a proper Migration chain before launch.
                .fallbackToDestructiveMigration(true)
                .build()
        }
    }
