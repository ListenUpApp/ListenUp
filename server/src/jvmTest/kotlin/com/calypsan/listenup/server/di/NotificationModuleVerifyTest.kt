@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.NotificationService
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.notifications.NotificationEmitter
import com.calypsan.listenup.server.push.NoOpPushNotifier
import com.calypsan.listenup.server.push.PushNotifier
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.time.Clock
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Verifies the notification Koin module resolves [NotificationService] and [NotificationEmitter],
 * and that the `createdAtStart` repository binding registers the `notifications` sync domain with
 * the [SyncRegistry] at bootstrap (the guarantee `SyncStreamService.listDomains()` relies on).
 */
class NotificationModuleVerifyTest :
    FunSpec({
        test("notificationModule resolves the service, emitter, and registers the sync domain at start") {
            withSqlDatabase {
                val registry = SyncRegistry()
                val app =
                    koinApplication {
                        modules(
                            module {
                                single<ListenUpDatabase> { sql }
                                single<Clock> { Clock.System }
                                single { ChangeBus() }
                                single { registry }
                                single<PushNotifier> { NoOpPushNotifier() }
                            },
                            notificationModule(),
                        )
                    }
                try {
                    app.koin.get<NotificationService>().shouldNotBeNull()
                    app.koin.get<NotificationEmitter>().shouldNotBeNull()
                    registry.knownDomains() shouldContain "notifications"
                } finally {
                    app.close()
                }
            }
        }
    })
