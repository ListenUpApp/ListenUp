package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.AdminUserRosterMaintainer
import com.calypsan.listenup.server.sync.AdminUserRosterRepository
import app.cash.sqldelight.db.SqlDriver
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the admin-only user-roster projection slice.
 *
 * [AdminUserRosterRepository] is bound `createdAtStart = true` so its `init` registers
 * the `"admin_user_roster"` sync domain with the [com.calypsan.listenup.server.sync.SyncRegistry]
 * at bootstrap. [AdminUserRosterMaintainer] rebuilds projection rows from `users`.
 *
 * Exposed as a **function** rather than a top-level `val` so each Koin container receives a
 * fresh [Module], preventing cross-container contamination in tests.
 */
fun adminUserRosterModule(): Module =
    module {
        single(createdAtStart = true) {
            AdminUserRosterRepository(
                db = get<ListenUpDatabase>(),
                bus = get(),
                registry = get(),
                driver = get<SqlDriver>(),
            )
        }
        single { AdminUserRosterMaintainer(sql = get<ListenUpDatabase>(), rosterRepo = get()) }
    }
