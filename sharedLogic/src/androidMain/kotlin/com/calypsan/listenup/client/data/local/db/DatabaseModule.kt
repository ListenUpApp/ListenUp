package com.calypsan.listenup.client.data.local.db

import android.content.Context
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
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_39_40
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_40_41
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_41_42
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_42_43
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_43_44
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_44_45
import com.calypsan.listenup.client.data.local.migrations.MIGRATION_45_46
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
                    MIGRATION_34_35,
                    MIGRATION_35_36,
                    MIGRATION_36_37,
                    MIGRATION_37_38,
                    MIGRATION_38_39,
                    MIGRATION_39_40,
                    MIGRATION_40_41,
                    MIGRATION_41_42,
                    MIGRATION_42_43,
                    MIGRATION_43_44,
                    MIGRATION_44_45,
                    MIGRATION_45_46,
                )
                // No public installs yet — every schema change nukes and re-creates local
                // data. Flip back to `false` + a proper Migration chain before launch.
                .fallbackToDestructiveMigration(true)
                .build()
        }
    }
