package com.calypsan.listenup.server.di

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.EntityService
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.EntityServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.EntityRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Story World wiring: the access-gated `entities` repository and [EntityService].
 *
 * [EntityRepository] is `createdAtStart` like every syncable repository, so it registers the `entities`
 * domain at boot. [EntityServiceImpl] carries a placeholder [PrincipalProvider] that fails loud; the RPC
 * route binds the authenticated caller per request via `copyWith`. A function, not a `val`, so each Koin
 * container gets fresh singletons.
 */
fun storyWorldModule(): Module =
    module {
        single(createdAtStart = true) {
            EntityRepository(
                db = get<ListenUpDatabase>(),
                bus = get(),
                registry = get(),
                driver = get<SqlDriver>(),
                clock = get(),
            )
        }
        single {
            EntityServiceImpl(
                entityRepo = get(),
                permissionPolicy = get<UserPermissionPolicy>(),
                accessPolicy = get<BookAccessPolicy>(),
                principal =
                    PrincipalProvider {
                        error(
                            "Unscoped EntityService — call copyWith(PrincipalProvider) at the route",
                        )
                    },
                clock = get(),
            )
        }
        single<EntityService> { get<EntityServiceImpl>() }
    }
