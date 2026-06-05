package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.ShelfReadAssembler
import com.calypsan.listenup.server.api.ShelfServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.sync.ShelfBookRepository
import com.calypsan.listenup.server.sync.ShelfRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the shelves slice — the userScoped shelf domain and its RPC service.
 *
 * [ShelfRepository] and [ShelfBookRepository] are bound `createdAtStart = true` (like
 * every [com.calypsan.listenup.server.sync.SyncableRepository]) so their `init` blocks
 * register the `"shelves"` / `"shelf_books"` sync domains with the [SyncRegistry]
 * [com.calypsan.listenup.server.sync.SyncRegistry] at bootstrap, making
 * `/api/v1/sync/domains` correct on the first request.
 *
 * [ShelfServiceImpl] carries the [unscopedPlaceholder] [PrincipalProvider]; the RPC
 * route binds the authenticated caller per-request via `copyWith`. [BookAccessPolicy]
 * and the [org.jetbrains.exposed.v1.jdbc.Database] are resolved from [syncModule].
 *
 * Exposed as a **function** for the same reason as [syncModule] — each Koin container
 * gets a fresh [Module] so singletons never leak across containers.
 */
fun shelfModule(): Module =
    module {
        single(createdAtStart = true) { ShelfRepository(get(), get(), get()) }
        single(createdAtStart = true) { ShelfBookRepository(get(), get(), get()) }
        single { ShelfReadAssembler(db = get()) }
        single {
            ShelfServiceImpl(
                shelfRepo = get(),
                shelfBookRepo = get(),
                bookAccessPolicy = get<BookAccessPolicy>(),
                readAssembler = get(),
                clock = get(),
                principal = unscopedShelfPlaceholder(),
                activityRecorder = getOrNull<ActivityRecorder>(),
            )
        }
        single<ShelfService> { get<ShelfServiceImpl>() }
    }

/**
 * The unscoped-caller placeholder the [ShelfServiceImpl] binding carries: a
 * [PrincipalProvider] that throws if invoked. The RPC route always `copyWith`s the
 * authenticated principal before calling, so reaching this placeholder signals a wiring
 * bug — fail loud rather than silently serving an unscoped view.
 */
private fun unscopedShelfPlaceholder(): PrincipalProvider =
    PrincipalProvider { error("Unscoped ShelfService — call copyWith(PrincipalProvider) at the route") }
