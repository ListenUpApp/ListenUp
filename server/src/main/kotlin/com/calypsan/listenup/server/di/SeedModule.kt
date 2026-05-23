package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.seed.PlaybackPositionDomainSeeder
import com.calypsan.listenup.server.seed.SeedRunner
import com.calypsan.listenup.server.seed.UserDomainSeeder
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the demo seed profile. Installed by `Application.module()` only
 * when `seed.profile = demo`. The `SeedRunner`'s seeder list is hand-assembled —
 * every future domain phase adds its `DomainSeeder` to this list.
 *
 * @param hasPlaybackModule whether the `:playback` slice is active (i.e., a library
 *   path is configured). When false, [PlaybackPositionDomainSeeder] is omitted from
 *   the runner — it depends on [com.calypsan.listenup.server.services.PlaybackPositionRepository]
 *   which is only bound when the playback module is loaded.
 */
fun seedModule(hasPlaybackModule: Boolean = false): Module =
    module {
        single { UserDomainSeeder(db = get(), authService = get()) }
        if (hasPlaybackModule) {
            single { PlaybackPositionDomainSeeder(db = get(), playbackPositionRepository = get()) }
        }
        single {
            val seeders =
                buildList {
                    add(get<UserDomainSeeder>())
                    if (hasPlaybackModule) add(get<PlaybackPositionDomainSeeder>())
                }
            SeedRunner(seeders = seeders)
        }
    }
