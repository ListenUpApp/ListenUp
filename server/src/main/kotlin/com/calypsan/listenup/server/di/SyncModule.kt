package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.CollectionShareRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Sync Foundation DI bindings. `createdAtStart = true` is mandatory on every
 * [com.calypsan.listenup.server.sync.SyncableRepository] so the `init` block
 * (which calls `registry.register(this)`) runs at application bootstrap
 * rather than lazily on first use — that makes `/api/v1/sync/domains` correct
 * on the first request.
 *
 * Exposed as a **function** rather than a top-level `val` so each Koin container
 * receives a fresh [Module] (and therefore a fresh `SingleInstanceFactory` per
 * binding). Koin's `SingleInstanceFactory` caches its produced instance on the
 * factory object itself — reusing one module across containers leaks the same
 * `SyncRegistry` (and every other `single`) into both, which is exactly the
 * cross-Koin contamination H3 is fixing.
 */
fun syncModule(): Module =
    module {
        single { SyncRegistry() }
        single(createdAtStart = true) { ChangeBus() }
        single(createdAtStart = true) { TagRepository(get(), get(), get()) }
        single(createdAtStart = true) { BookTagRepository(get(), get(), get()) }
        single(createdAtStart = true) { CollectionRepository(get(), get(), get()) }
        single(createdAtStart = true) { CollectionBookRepository(get(), get(), get()) }
        single(createdAtStart = true) { CollectionShareRepository(get(), get(), get()) }
    }
