@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.di

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.CampfireService
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.auth.UserRoleLookup
import com.calypsan.listenup.server.campfire.CampfireRegistry
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.push.NoOpPushNotifier
import com.calypsan.listenup.server.push.PushNotifier
import com.calypsan.listenup.server.scheduler.CampfireReaperTask
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.ActivitySyncRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.time.Clock
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Verifies [campfireModule] resolves [CampfireService], [CampfireRegistry], and
 * [CampfireReaperTask] from a minimal Koin graph — the same DI-level guarantee
 * [PushModuleVerifyTest] gives the push slice.
 */
class CampfireModuleVerifyTest :
    FunSpec({
        test("campfireModule binds CampfireService, CampfireRegistry, and CampfireReaperTask") {
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val app =
                    koinApplication {
                        modules(
                            module {
                                single<ListenUpDatabase> { sql }
                                single<SqlDriver> { driver }
                                single<Clock> { Clock.System }
                                single<ChangeBus> { bus }
                                single { BookAccessPolicy(db = get(), driver = get()) }
                                single { PlaybackPositionRepository(db = get(), bus = bus, registry = registry, clock = get()) }
                                single { PublicProfileRepository(db = get(), bus = bus, registry = registry) }
                                single { UserRoleLookup(db = get()) }
                                single<PushNotifier> { NoOpPushNotifier() }
                                single { ActivitySyncRepository(db = get(), bus = bus, registry = registry, driver = get()) }
                                single { ActivityRecorder(syncRepo = get()) }
                            },
                            campfireModule(),
                        )
                    }
                try {
                    app.koin.get<CampfireRegistry>().shouldNotBeNull()
                    app.koin.get<CampfireService>().shouldNotBeNull()
                    app.koin.get<CampfireReaperTask>().shouldNotBeNull()
                } finally {
                    app.close()
                }
            }
        }
    })
