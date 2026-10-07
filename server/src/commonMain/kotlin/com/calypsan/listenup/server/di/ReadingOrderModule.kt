package com.calypsan.listenup.server.di

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.ReadingOrderService
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.ReadingOrderServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ReadingOrderBookRepository
import com.calypsan.listenup.server.sync.ReadingOrderFollowRepository
import com.calypsan.listenup.server.sync.ReadingOrderRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for reading orders (#962): the three syncable repositories and the RPC service.
 *
 * The repositories are bound `createdAtStart = true`, like every syncable repository, so they register
 * the `reading_orders`, `reading_order_books` and `reading_order_follows` sync domains at bootstrap and
 * `SyncStreamService.listDomains()` is correct on the first request. [ReadingOrderServiceImpl] carries a
 * placeholder [PrincipalProvider] that fails loud; the RPC route binds the caller per request via
 * `copyWith`.
 *
 * Exposed as a function so each Koin container gets a fresh [Module].
 */
fun readingOrderModule(): Module =
    module {
        single(createdAtStart = true) { ReadingOrderRepository(get<ListenUpDatabase>(), get(), get()) }
        single(createdAtStart = true) {
            ReadingOrderBookRepository(get<ListenUpDatabase>(), get(), get(), driver = get<SqlDriver>())
        }
        single(createdAtStart = true) { ReadingOrderFollowRepository(get<ListenUpDatabase>(), get(), get()) }
        single {
            ReadingOrderServiceImpl(
                orders = get(),
                members = get(),
                follows = get(),
                seriesRepo = get(),
                sqlDb = get<ListenUpDatabase>(),
                accessPolicy = get<BookAccessPolicy>(),
                permissionPolicy = get<UserPermissionPolicy>(),
                principal = unscopedReadingOrderPlaceholder(),
                clock = get(),
            )
        }
        single<ReadingOrderService> { get<ReadingOrderServiceImpl>() }
    }

/**
 * The unscoped-caller placeholder: a [PrincipalProvider] that throws if invoked. The RPC route always
 * `copyWith`s the authenticated principal before calling, so reaching this signals a wiring bug.
 */
private fun unscopedReadingOrderPlaceholder(): PrincipalProvider =
    PrincipalProvider { error("ReadingOrderService called without a scoped principal — the route must copyWith(it)") }
