package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.WorldEventService
import com.calypsan.listenup.server.api.WorldEventServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.WorldEventRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the Story World unified event log slice — the library-shared world-event
 * domain and its RPC service. Mirrors [entityModule], minus the userScoped dimension: events have
 * no owner, so there is no `*BookRepository`/follow-state sibling to wire.
 *
 * [WorldEventRepository] is bound `createdAtStart = true` (like every
 * [com.calypsan.listenup.server.sync.SyncableRepository]) so its `init` block registers the
 * `"world_events"` sync domain with the [com.calypsan.listenup.server.sync.SyncRegistry] at
 * bootstrap, making `/api/v1/sync/domains` correct on the first request.
 *
 * [WorldEventServiceImpl] carries the [unscopedWorldEventPlaceholder] [PrincipalProvider]; the
 * RPC route binds the authenticated caller per-request via `copyWith`. [UserPermissionPolicy] and
 * the database are resolved from [booksModule]/[syncModule].
 *
 * Exposed as a **function** for the same reason as [entityModule] — each Koin container gets a
 * fresh [Module] so singletons never leak across containers.
 */
fun worldEventModule(): Module =
    module {
        single(createdAtStart = true) { WorldEventRepository(get<ListenUpDatabase>(), get(), get()) }
        single {
            WorldEventServiceImpl(
                worldEventRepo = get(),
                permissionPolicy = get<UserPermissionPolicy>(),
                principal = unscopedWorldEventPlaceholder(),
            )
        }
        single<WorldEventService> { get<WorldEventServiceImpl>() }
    }

/**
 * The unscoped-caller placeholder the [WorldEventServiceImpl] binding carries: a
 * [PrincipalProvider] that throws if invoked. The RPC route always `copyWith`s the authenticated
 * principal before calling, so reaching this placeholder signals a wiring bug — fail loud rather
 * than silently serving an unscoped view.
 */
private fun unscopedWorldEventPlaceholder(): PrincipalProvider =
    PrincipalProvider { error("Unscoped WorldEventService — call copyWith(PrincipalProvider) at the route") }
