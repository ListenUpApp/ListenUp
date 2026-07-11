package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.ReadingOrderService
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.ReadingOrderReadAssembler
import com.calypsan.listenup.server.api.ReadingOrderServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.sync.ReadingOrderBookRepository
import com.calypsan.listenup.server.sync.ReadingOrderRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the reading-orders slice — the userScoped reading-order domain
 * and its RPC service. Mirrors [shelfModule].
 *
 * [ReadingOrderRepository] and [ReadingOrderBookRepository] are bound
 * `createdAtStart = true` (like every
 * [com.calypsan.listenup.server.sync.SyncableRepository]) so their `init` blocks
 * register the `"reading_orders"` / `"reading_order_books"` sync domains with the
 * [com.calypsan.listenup.server.sync.SyncRegistry] at bootstrap, making
 * `/api/v1/sync/domains` correct on the first request.
 *
 * [ReadingOrderServiceImpl] carries the [unscopedReadingOrderPlaceholder]
 * [PrincipalProvider]; the RPC route binds the authenticated caller per-request via
 * `copyWith`. [BookAccessPolicy] and the database are resolved from [syncModule].
 *
 * Exposed as a **function** for the same reason as [syncModule] — each Koin
 * container gets a fresh [Module] so singletons never leak across containers.
 */
fun readingOrderModule(): Module =
    module {
        // ReadingOrder + ReadingOrderBook are SQLDelight conversions — they resolve
        // [ListenUpDatabase], not the Exposed [Database] the service layer still uses.
        single(createdAtStart = true) { ReadingOrderRepository(get<ListenUpDatabase>(), get(), get()) }
        single(createdAtStart = true) { ReadingOrderBookRepository(get<ListenUpDatabase>(), get(), get()) }
        single { ReadingOrderReadAssembler(sql = get<ListenUpDatabase>()) }
        single {
            ReadingOrderServiceImpl(
                readingOrderRepo = get(),
                readingOrderBookRepo = get(),
                bookAccessPolicy = get<BookAccessPolicy>(),
                readAssembler = get(),
                clock = get(),
                principal = unscopedReadingOrderPlaceholder(),
                activityRecorder = getOrNull<ActivityRecorder>(),
            )
        }
        single<ReadingOrderService> { get<ReadingOrderServiceImpl>() }
    }

/**
 * The unscoped-caller placeholder the [ReadingOrderServiceImpl] binding carries: a
 * [PrincipalProvider] that throws if invoked. The RPC route always `copyWith`s the
 * authenticated principal before calling, so reaching this placeholder signals a
 * wiring bug — fail loud rather than silently serving an unscoped view.
 */
private fun unscopedReadingOrderPlaceholder(): PrincipalProvider =
    PrincipalProvider { error("Unscoped ReadingOrderService — call copyWith(PrincipalProvider) at the route") }
