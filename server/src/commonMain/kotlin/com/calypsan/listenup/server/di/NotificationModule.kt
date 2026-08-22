package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.NotificationService
import com.calypsan.listenup.server.api.NotificationServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.notifications.NotificationEmitter
import com.calypsan.listenup.server.notifications.NotificationPrefsRepository
import com.calypsan.listenup.server.sync.NotificationRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the notifications slice — the userScoped inbox domain, per-type preferences,
 * the [NotificationEmitter] seam, and the RPC service.
 *
 * [NotificationRepository] is bound `createdAtStart = true` (like every
 * [com.calypsan.listenup.server.sync.SyncableRepository]) — its `init` registers the
 * `"notifications"` sync domain with the [com.calypsan.listenup.server.sync.SyncRegistry] at
 * bootstrap, making `SyncStreamService.listDomains()` correct on the first request.
 *
 * The package split is deliberate: row storage lives in `sync/` (the syncable substrate the
 * firehose and cursored pull fan out), while preference storage and the emitter orchestration
 * live in `notifications/` (delivery policy, not sync plumbing).
 *
 * `notifier = get()` always resolves — [pushModule] binds a [com.calypsan.listenup.server.push.PushNotifier]
 * unconditionally ([com.calypsan.listenup.server.push.NoOpPushNotifier] when no relay is configured).
 *
 * [NotificationServiceImpl] carries the [unscopedNotificationPlaceholder] [PrincipalProvider];
 * the RPC route binds the authenticated caller per-request via `copyWith`.
 *
 * Exposed as a **function** for the same reason as [syncModule] — each Koin container gets a
 * fresh [Module] so singletons never leak across containers.
 */
fun notificationModule(): Module =
    module {
        single(createdAtStart = true) { NotificationRepository(get<ListenUpDatabase>(), get(), get()) }
        single { NotificationPrefsRepository(get<ListenUpDatabase>()) }
        single { NotificationEmitter(db = get<ListenUpDatabase>(), repo = get(), prefs = get(), notifier = get()) }
        single {
            NotificationServiceImpl(
                repo = get(),
                prefs = get(),
                clock = get(),
                principal = unscopedNotificationPlaceholder(),
            )
        }
        single<NotificationService> { get<NotificationServiceImpl>() }
    }

/**
 * The unscoped-caller placeholder the [NotificationServiceImpl] binding carries: a
 * [PrincipalProvider] that throws if invoked. The RPC route always `copyWith`s the
 * authenticated principal before calling, so reaching this placeholder signals a wiring
 * bug — fail loud rather than silently serving an unscoped view.
 */
private fun unscopedNotificationPlaceholder(): PrincipalProvider =
    PrincipalProvider { error("Unscoped NotificationService — call copyWith(PrincipalProvider) at the route") }
