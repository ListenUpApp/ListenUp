package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.UserPreferencesService
import com.calypsan.listenup.server.api.UserPreferencesServiceImpl
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import org.koin.core.module.Module
import org.koin.dsl.module

/** Per-user playback-preferences RPC service (issue #599). */
fun userPreferencesModule(): Module =
    module {
        single<UserPreferencesService> {
            // The bus lets a successful update nudge the user's other devices to re-pull live.
            UserPreferencesServiceImpl(sql = get<ListenUpDatabase>(), clock = get(), bus = get<ChangeBus>())
        }
    }
