package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.seed.ContributorEnrichmentSeeder
import com.calypsan.listenup.server.seed.LibraryDomainSeeder
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
 * @param hasBooksModule whether the `:books` slice is active (i.e., a library
 *   path is configured). When false, [ContributorEnrichmentSeeder] is omitted —
 *   it depends on [com.calypsan.listenup.server.services.ContributorRepository]
 *   which is only bound when the books module is loaded.
 * @param demoLibraryPath absolute path to the pre-generated synthetic library
 *   (typically `build/seed-library`). When non-null, [LibraryDomainSeeder] is
 *   registered and seeds a "Demo Library" pointing at that path. When null
 *   (synthetic library not yet generated), the library seeder is omitted and
 *   the library will be empty until the next restart after generation.
 */
fun seedModule(
    hasPlaybackModule: Boolean = false,
    hasBooksModule: Boolean = false,
    demoLibraryPath: String? = null,
): Module =
    module {
        single { UserDomainSeeder(db = get(), authService = get()) }
        if (demoLibraryPath != null) {
            single { LibraryDomainSeeder(db = get(), libraryAdminService = get(), demoLibraryPath = demoLibraryPath) }
        }
        if (hasPlaybackModule) {
            single { PlaybackPositionDomainSeeder(db = get(), playbackPositionRepository = get()) }
            single { ListeningEventDomainSeeder(db = get(), listeningEventRepository = get()) }
        }
        if (hasBooksModule) {
            single { ContributorEnrichmentSeeder(db = get(), contributorRepository = get()) }
        }
        single {
            val seeders =
                buildList {
                    add(get<UserDomainSeeder>())
                    if (demoLibraryPath != null) {
                        add(get<LibraryDomainSeeder>())
                    }
                    if (hasPlaybackModule) {
                        add(get<PlaybackPositionDomainSeeder>())
                        add(get<ListeningEventDomainSeeder>())
                    }
                    if (hasBooksModule) {
                        add(get<ContributorEnrichmentSeeder>())
                    }
                }
            SeedRunner(seeders = seeders)
        }
    }
