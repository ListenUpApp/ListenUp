package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.seed.ContributorEnrichmentSeeder
import com.calypsan.listenup.server.seed.DomainSeeder
import com.calypsan.listenup.server.seed.GenreDomainSeeder
import com.calypsan.listenup.server.seed.LibraryDomainSeeder
import com.calypsan.listenup.server.seed.ListeningEventDomainSeeder
import com.calypsan.listenup.server.seed.PlaybackPositionDomainSeeder
import com.calypsan.listenup.server.seed.SeedRunner
import com.calypsan.listenup.server.seed.TagDomainSeeder
import com.calypsan.listenup.server.seed.UserDomainSeeder
import org.koin.core.module.Module
import org.koin.core.scope.Scope
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
 * @param hasTagsModule whether the `:tags` slice is active. When true, [TagDomainSeeder]
 *   is registered to seed a curated set of demo tags. Tags are independent of the
 *   scanner, so this can be true even without a library configured.
 * @param hasGenresModule whether the `:genres` slice is active. When true,
 *   [GenreDomainSeeder] is registered to seed the default genre taxonomy (3 roots,
 *   ~70 nodes total) ported from Go's `internal/genre/defaults.go`. Like tags, the
 *   genre tree is curator-controlled and independent of the scanner.
 */
fun seedModule(
    hasPlaybackModule: Boolean = false,
    hasBooksModule: Boolean = false,
    demoLibraryPath: String? = null,
    hasTagsModule: Boolean = false,
    hasGenresModule: Boolean = false,
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
        if (hasTagsModule) {
            single { TagDomainSeeder(db = get(), tagRepository = get()) }
        }
        // GenreDomainSeeder is bound in booksModule (it runs on every install, not just demo),
        // so we don't bind it here — but the runner still includes it for demo.
        single {
            SeedRunner(
                seeders =
                    assembleSeeders(
                        koin = this,
                        hasPlaybackModule = hasPlaybackModule,
                        hasBooksModule = hasBooksModule,
                        demoLibraryPath = demoLibraryPath,
                        hasTagsModule = hasTagsModule,
                        hasGenresModule = hasGenresModule,
                    ),
            )
        }
    }

/**
 * Assembles the ordered list of [DomainSeeder]s from the bound singletons. Pulled out
 * of [seedModule]'s lambda so the per-flag conditional chain doesn't inflate the
 * cognitive complexity of the module-builder function.
 */
@Suppress("LongParameterList")
private fun assembleSeeders(
    koin: Scope,
    hasPlaybackModule: Boolean,
    hasBooksModule: Boolean,
    demoLibraryPath: String?,
    hasTagsModule: Boolean,
    hasGenresModule: Boolean,
): List<DomainSeeder> =
    buildList {
        add(koin.get<UserDomainSeeder>())
        if (demoLibraryPath != null) {
            add(koin.get<LibraryDomainSeeder>())
        }
        if (hasPlaybackModule) {
            add(koin.get<PlaybackPositionDomainSeeder>())
            add(koin.get<ListeningEventDomainSeeder>())
        }
        if (hasBooksModule) {
            add(koin.get<ContributorEnrichmentSeeder>())
        }
        if (hasTagsModule) {
            add(koin.get<TagDomainSeeder>())
        }
        if (hasGenresModule) {
            add(koin.get<GenreDomainSeeder>())
        }
    }
