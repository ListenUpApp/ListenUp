package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.CampfireService
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.CampfireServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.campfire.CampfireRegistry
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.scheduler.CampfireReaperTask
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the Campfire (co-listening) slice: the in-memory [CampfireRegistry], the
 * access-gated [CampfireService], and the [CampfireReaperTask] that periodically sweeps away-grace
 * evictions and idle rooms.
 *
 * [CampfireRegistry] is deliberately NOT wired to [ChangeBus] itself (its own class KDoc) — the
 * `CampfiresChanged` discovery nudge is broadcast by [CampfireServiceImpl] (create/end/leave-to-empty)
 * and by [CampfireReaperTask] (reaper-driven endings) instead, both of which take a [ChangeBus] here.
 */
fun campfireModule(): Module =
    module {
        single { CampfireRegistry(clock = get()) }
        single {
            CampfireServiceImpl(
                registry = get(),
                bookAccessPolicy = get<BookAccessPolicy>(),
                playbackPositions = get<PlaybackPositionRepository>(),
                publicProfiles = get<PublicProfileRepository>(),
                db = get<ListenUpDatabase>(),
                bus = get<ChangeBus>(),
                clock = get(),
                principal =
                    PrincipalProvider {
                        error("Unscoped CampfireService — call copyWith(PrincipalProvider) at the route")
                    },
            )
        }
        single<CampfireService> { get<CampfireServiceImpl>() }
        single { CampfireReaperTask(registry = get(), bus = get<ChangeBus>(), clock = get()) }
    }
