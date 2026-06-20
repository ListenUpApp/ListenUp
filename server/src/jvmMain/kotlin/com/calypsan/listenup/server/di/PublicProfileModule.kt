package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.sync.PublicProfileRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the public-profile projection slice.
 *
 * [PublicProfileRepository] is bound `createdAtStart = true` so its `init` registers
 * the `"public_profiles"` sync domain with the [com.calypsan.listenup.server.sync.SyncRegistry]
 * at bootstrap. [PublicProfileMaintainer] rebuilds projection rows from `users` + `user_stats`.
 *
 * Exposed as a **function** rather than a top-level `val` so each Koin container receives a
 * fresh [Module], preventing cross-container contamination in tests.
 */
fun publicProfileModule(): Module =
    module {
        single(createdAtStart = true) { PublicProfileRepository(get<ListenUpDatabase>(), get(), get()) }
        single { PublicProfileMaintainer(sql = get<ListenUpDatabase>(), db = get(), publicProfileRepo = get()) }
    }
