package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.UserPreferencesService
import com.calypsan.listenup.server.api.UserPreferencesServiceImpl
import org.koin.core.module.Module
import org.koin.dsl.module

/** Per-user playback-preferences RPC service (issue #599). */
fun userPreferencesModule(): Module =
    module {
        single<UserPreferencesService> {
            UserPreferencesServiceImpl(db = get(), clock = get())
        }
    }
