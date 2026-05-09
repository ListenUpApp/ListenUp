package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.TagRepository
import org.koin.dsl.module

/**
 * Sync Foundation DI bindings. `createdAtStart = true` is mandatory on every
 * [com.calypsan.listenup.server.sync.SyncableRepository] so the `init` block
 * (which calls `SyncRoutes.register(...)`) runs at application bootstrap
 * rather than lazily on first use — that makes `/api/v1/sync/domains` correct
 * on the first request.
 */
val syncModule =
    module {
        single(createdAtStart = true) { ChangeBus() }
        single(createdAtStart = true) { TagRepository(get(), get()) }
    }
