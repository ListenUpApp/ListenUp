package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.api.InviteServiceImpl
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.seed.ActiveSessionSeeder
import com.calypsan.listenup.server.seed.ActivitySeeder
import com.calypsan.listenup.server.seed.CollectionDomainSeeder
import com.calypsan.listenup.server.seed.ContributorEnrichmentSeeder
import com.calypsan.listenup.server.seed.DomainSeeder
import com.calypsan.listenup.server.seed.GenreDomainSeeder
import com.calypsan.listenup.server.seed.InviteDomainSeeder
import com.calypsan.listenup.server.seed.ListeningEventDomainSeeder
import com.calypsan.listenup.server.seed.MoodDomainSeeder
import com.calypsan.listenup.server.seed.PlaybackPositionDomainSeeder
import com.calypsan.listenup.server.seed.PublicProfileDomainSeeder
import com.calypsan.listenup.server.seed.SeedRunner
import com.calypsan.listenup.server.seed.ShelfDomainSeeder
import com.calypsan.listenup.server.seed.TagDomainSeeder
import com.calypsan.listenup.server.seed.UserDomainSeeder
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module

/**
 * Koin module for the demo seed profile. Installed by `Application.module()` only
 * when `seed.profile = demo`. The `SeedRunner`'s seeder list is hand-assembled —
 * each domain adds its `DomainSeeder` to this list.
 *
 * @param hasPlaybackModule whether the `:playback` slice is active (i.e., a library
 *   path is configured). When false, [PlaybackPositionDomainSeeder],
 *   [ListeningEventDomainSeeder], [ActiveSessionSeeder] and [ActivitySeeder] are omitted
 *   from the runner — they depend on
 *   [com.calypsan.listenup.server.services.PlaybackPositionRepository],
 *   [com.calypsan.listenup.server.services.ListeningEventRepository],
 *   [com.calypsan.listenup.server.services.ActiveSessionRepository] and
 *   [com.calypsan.listenup.server.services.ActivityRepository] respectively, which are
 *   only bound when the playback module is loaded.
 * @param hasBooksModule whether the `:books` slice is active (i.e., a library
 *   path is configured). When false, [ContributorEnrichmentSeeder] is omitted —
 *   it depends on [com.calypsan.listenup.server.services.ContributorRepository]
 *   which is only bound when the books module is loaded.
 * @param hasTagsModule whether the `:tags` slice is active. When true, [TagDomainSeeder]
 *   is registered to seed a curated set of demo tags. Tags are independent of the
 *   scanner, so this can be true even without a library configured.
 * @param hasGenresModule whether the `:genres` slice is active. When true,
 *   [GenreDomainSeeder] is registered to seed the default genre taxonomy (3 roots,
 *   ~70 nodes total). Like tags, the genre tree is curator-controlled and
 *   independent of the scanner.
 * @param hasMoodsModule whether the moods slice is active. When true,
 *   [MoodDomainSeeder] is registered to seed the canonical Audible mood vocabulary
 *   (≈24 affective labels). Like genres, moods are curator-controlled dedupe anchors
 *   and independent of the scanner. The moods bindings live in [booksModule], so this
 *   is true exactly when [hasBooksModule] is.
 * @param hasCollectionsModule whether the collections slice is active. When true,
 *   [CollectionDomainSeeder] is registered to seed the demo library's inbox plus one
 *   demo collection. The collections bindings live in [booksModule], so this is true
 *   exactly when [hasBooksModule] is.
 * @param hasShelvesModule whether the shelves slice is active. When true,
 *   [ShelfDomainSeeder] is registered to seed demo shelves for the demo user (a public
 *   shelf + a private shelf so the discovery + privacy surfaces have demo data).
 *
 * [PublicProfileDomainSeeder] is always registered — [publicProfileModule] is always
 * active, so [com.calypsan.listenup.server.services.PublicProfileMaintainer] is always
 * available to the seeder.
 */
fun seedModule(
    hasPlaybackModule: Boolean = false,
    hasBooksModule: Boolean = false,
    hasTagsModule: Boolean = false,
    hasGenresModule: Boolean = false,
    hasMoodsModule: Boolean = false,
    hasCollectionsModule: Boolean = false,
    hasShelvesModule: Boolean = false,
): Module =
    module {
        single { UserDomainSeeder(sql = get(), authService = get()) }
        single { InviteDomainSeeder(db = get<ListenUpDatabase>(), inviteService = get<InviteServiceImpl>()) }
        if (hasPlaybackModule) {
            single { PlaybackPositionDomainSeeder(sql = get(), playbackPositionRepository = get()) }
            single { ListeningEventDomainSeeder(sql = get(), listeningEventRepository = get()) }
            single { ActiveSessionSeeder(sql = get(), activeSessionRepository = get()) }
            single { ActivitySeeder(sql = get(), activityRecorder = get()) }
        }
        if (hasBooksModule) {
            single { ContributorEnrichmentSeeder(sql = get(), contributorRepository = get()) }
        }
        if (hasTagsModule) {
            single { TagDomainSeeder(sql = get(), tagRepository = get()) }
        }
        if (hasCollectionsModule) {
            single {
                CollectionDomainSeeder(
                    sql = get(),
                    collectionRepo = get(),
                    collectionService = get<CollectionService>() as CollectionServiceImpl,
                )
            }
        }
        if (hasShelvesModule) {
            single { ShelfDomainSeeder(sql = get(), shelfRepo = get()) }
        }
        // publicProfileModule() is always active, so PublicProfileMaintainer is always bound.
        single { PublicProfileDomainSeeder(sql = get(), publicProfileMaintainer = get()) }
        // GenreDomainSeeder is bound in booksModule (it runs on every install, not just demo),
        // so we don't bind it here — but the runner still includes it for demo.
        single {
            SeedRunner(
                seeders =
                    assembleSeeders(
                        koin = this,
                        hasPlaybackModule = hasPlaybackModule,
                        hasBooksModule = hasBooksModule,
                        hasTagsModule = hasTagsModule,
                        hasGenresModule = hasGenresModule,
                        hasMoodsModule = hasMoodsModule,
                        hasCollectionsModule = hasCollectionsModule,
                        hasShelvesModule = hasShelvesModule,
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
    hasTagsModule: Boolean,
    hasGenresModule: Boolean,
    hasMoodsModule: Boolean,
    hasCollectionsModule: Boolean,
    hasShelvesModule: Boolean,
): List<DomainSeeder> =
    buildList {
        add(koin.get<UserDomainSeeder>())
        add(koin.get<InviteDomainSeeder>())
        if (hasPlaybackModule) {
            add(koin.get<PlaybackPositionDomainSeeder>())
            add(koin.get<ListeningEventDomainSeeder>())
            add(koin.get<ActiveSessionSeeder>())
            add(koin.get<ActivitySeeder>())
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
        if (hasMoodsModule) {
            add(koin.get<MoodDomainSeeder>())
        }
        if (hasCollectionsModule) {
            add(koin.get<CollectionDomainSeeder>())
        }
        if (hasShelvesModule) {
            add(koin.get<ShelfDomainSeeder>())
        }
        add(koin.get<PublicProfileDomainSeeder>())
    }
