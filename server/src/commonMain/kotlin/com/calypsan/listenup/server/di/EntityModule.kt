package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.EntityService
import com.calypsan.listenup.server.api.EntityServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.EntityRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the Story World entities slice — the library-shared entity domain
 * and its RPC service. Mirrors [readingOrderModule], minus the userScoped dimension:
 * entities have no owner, so there is no `*BookRepository`/follow-state sibling to wire.
 *
 * [EntityRepository] is bound `createdAtStart = true` (like every
 * [com.calypsan.listenup.server.sync.SyncableRepository]) so its `init` block registers
 * the `"entities"` sync domain with the
 * [com.calypsan.listenup.server.sync.SyncRegistry] at bootstrap, making
 * `/api/v1/sync/domains` correct on the first request.
 *
 * [EntityServiceImpl] carries the [unscopedEntityPlaceholder] [PrincipalProvider]; the
 * RPC route binds the authenticated caller per-request via `copyWith`.
 * [UserPermissionPolicy] and the database are resolved from [booksModule]/[syncModule].
 *
 * Exposed as a **function** for the same reason as [readingOrderModule] — each Koin
 * container gets a fresh [Module] so singletons never leak across containers.
 */
fun entityModule(): Module =
    module {
        single(createdAtStart = true) { EntityRepository(get<ListenUpDatabase>(), get(), get()) }
        single {
            EntityServiceImpl(
                entityRepo = get(),
                permissionPolicy = get<UserPermissionPolicy>(),
                principal = unscopedEntityPlaceholder(),
            )
        }
        single<EntityService> { get<EntityServiceImpl>() }
    }

/**
 * The unscoped-caller placeholder the [EntityServiceImpl] binding carries: a
 * [PrincipalProvider] that throws if invoked. The RPC route always `copyWith`s the
 * authenticated principal before calling, so reaching this placeholder signals a
 * wiring bug — fail loud rather than silently serving an unscoped view.
 */
private fun unscopedEntityPlaceholder(): PrincipalProvider =
    PrincipalProvider { error("Unscoped EntityService — call copyWith(PrincipalProvider) at the route") }
