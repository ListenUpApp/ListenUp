package com.calypsan.listenup.server.di

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.OrphanParentPurger
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncStreamServiceImpl
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
 * library wiring. Its dependencies — the [ListenUpDatabase] and the shared [SqlDriver] — are
 * always bound.
 */
fun syncModule(): Module =
    module {
        single { SyncRegistry() }
        single { BookAccessPolicy(db = get<ListenUpDatabase>(), driver = get<SqlDriver>()) }
        single(createdAtStart = true) { ChangeBus() }
        // The RPC firehose over the same bus the SSE route streams from. The policy travels as a
        // thunk (resolved lazily, only when a book-gated event must be probed) — mirrors the SSE
        // route's thunk so harnesses driving only ungated domains need no BookAccessPolicy.
        single<SyncStreamService> {
            SyncStreamServiceImpl(
                bus = get(),
                bookAccessPolicy = { get() },
            )
        }
        // Tag + BookTag + Mood + BookMood are SQLDelight conversions (the cutover template):
        // they resolve [ListenUpDatabase], not the Exposed [Database] the other repos use.
        single(createdAtStart = true) { TagRepository(get<ListenUpDatabase>(), get(), get()) }
        single(createdAtStart = true) { BookTagRepository(get<ListenUpDatabase>(), get(), get()) }
        single(createdAtStart = true) { MoodRepository(get<ListenUpDatabase>(), get(), get()) }
        single(createdAtStart = true) { BookMoodRepository(get<ListenUpDatabase>(), get(), get()) }
        // Orphan-purge collaborator, co-located with the tag/mood/junction repos it reads: when a
        // book removal leaves a parent (contributor/series/genre/tag/mood) with zero live children,
        // BookRepository.softDelete captures the parents, then this tombstones the orphaned ones.
        single {
            OrphanParentPurger(
                db = get<ListenUpDatabase>(),
                contributorRepository = get(),
                seriesRepository = get(),
                genreRepository = get<GenreRepository>(),
                tagRepository = getOrNull<TagRepository>(),
                moodRepository = getOrNull<MoodRepository>(),
            )
        }
        // Collection aggregate — fully SQLDelight. The access-filtered catch-up/digest raw reads
        // now run engine-neutral over the shared [SqlDriver] (the firehose's runtime-built
        // `extraWhere` subquery carries plain raw args; see each repo's pullSince override), so
        // these repos no longer hold an Exposed [Database].
        single(createdAtStart = true) {
            CollectionRepository(get<ListenUpDatabase>(), get(), get(), driver = get<SqlDriver>())
        }
        single(createdAtStart = true) {
            CollectionBookRepository(get<ListenUpDatabase>(), get(), get(), driver = get<SqlDriver>())
        }
        single(createdAtStart = true) {
            CollectionGrantRepository(get<ListenUpDatabase>(), get(), get(), driver = get<SqlDriver>())
        }
    }
