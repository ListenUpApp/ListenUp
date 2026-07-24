package com.calypsan.listenup.client.data.local.db

import android.content.Context
import androidx.room3.Room
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android database location: the app's private `databases/` directory.
 *
 * Construction lives in [buildConfigured] — this module only resolves the path.
 */
internal actual val platformDatabaseModule: Module =
    module {
        single {
            val context: Context = get()
            Room
                .databaseBuilder<ListenUpDatabase>(
                    context = context.applicationContext,
                    name = context.getDatabasePath("listenup.db").absolutePath,
                ).buildConfigured()
        }
    }
