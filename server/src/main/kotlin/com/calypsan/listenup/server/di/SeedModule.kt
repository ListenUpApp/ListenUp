package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.seed.ListeningEventDomainSeeder
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
 *   path is configured). When false, [PlaybackPositionDomainSeeder] and
 *   [ListeningEventDomainSeeder] are omitted from the runner — they depend on
 *   [com.calypsan.listenup.server.services.PlaybackPositionRepository] and
 *   [com.calypsan.listenup.server.services.ListeningEventRepository] which are only
 *   bound when the playback module is loaded.
 */
fun seedModule(hasPlaybackModule: Boolean = false): Module =
    module {
        single { UserDomainSeeder(db = get(), authService = get()) }
        if (hasPlaybackModule) {
            single { PlaybackPositionDomainSeeder(db = get(), playbackPositionRepository = get()) }
            single { ListeningEventDomainSeeder(db = get(), listeningEventRepository = get()) }
        }
        single {
            val seeders =
                buildList {
                    add(get<UserDomainSeeder>())
                    if (hasPlaybackModule) {
                        add(get<PlaybackPositionDomainSeeder>())
                        add(get<ListeningEventDomainSeeder>())
                    }
                }
            SeedRunner(seeders = seeders)
        }
    }
