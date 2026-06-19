package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.CollectionShareRepository
import com.calypsan.listenup.server.sync.MoodRepository
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
 *
 * [BookAccessPolicy] lives here rather than in `booksModule` because it is a sync
 * concern: the always-mounted catch-up/digest/firehose seams resolve it to scope the
 * `books` and three `collection_*` domains to a viewer. Binding it in the (historically
 * library-gated) `booksModule` once left the policy unresolved on a library-less boot —
 * the catch-up route 500'd with `NoDefinitionFoundException` for the first non-admin
 * caller. `syncModule` is the always-loaded home that keeps it resolvable regardless of
 * library wiring. Its sole dependency is the [Database], always bound.
 */
fun syncModule(): Module =
    module {
        single { SyncRegistry() }
        single { BookAccessPolicy(get()) }
        single(createdAtStart = true) { ChangeBus() }
        // Tag + BookTag are the first SQLDelight conversions (the cutover template):
        // they resolve [ListenUpDatabase], not the Exposed [Database] the other repos use.
        single(createdAtStart = true) { TagRepository(get<ListenUpDatabase>(), get(), get()) }
        single(createdAtStart = true) { BookTagRepository(get<ListenUpDatabase>(), get(), get()) }
        single(createdAtStart = true) { MoodRepository(get(), get(), get()) }
        single(createdAtStart = true) { BookMoodRepository(get(), get(), get()) }
        single(createdAtStart = true) { CollectionRepository(get(), get(), get()) }
        single(createdAtStart = true) { CollectionBookRepository(get(), get(), get()) }
        single(createdAtStart = true) { CollectionShareRepository(get(), get(), get()) }
    }
