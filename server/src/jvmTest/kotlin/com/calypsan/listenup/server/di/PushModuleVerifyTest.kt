@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.PushService
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.push.NoOpPushNotifier
import com.calypsan.listenup.server.push.PushConfig
import com.calypsan.listenup.server.push.PushNotifier
import com.calypsan.listenup.server.push.RelayPushNotifier
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Verifies the push Koin module resolves [PushService] and selects the correct [PushNotifier]
 * implementation from [PushConfig.configured] — the DI-level guarantee that a relay-less fork
 * never evaluates [PushConfig.relayUrl]`!!` (see [pushModule] KDoc).
 */
class PushModuleVerifyTest :
    FunSpec({
        test("pushModule binds RelayPushNotifier when a relay URL is configured") {
            withSqlDatabase {
                val app =
                    koinApplication {
                        modules(
                            module {
                                single<ListenUpDatabase> { sql }
                                single<Clock> { Clock.System }
                                single { ServerSettingsRepository(get<ListenUpDatabase>(), RegistrationPolicy.CLOSED) }
                                single { PushConfig(relayUrl = "https://push.example.com") }
                            },
                            pushModule(),
                        )
                    }
                try {
                    app.koin.get<PushNotifier>().shouldBeInstanceOf<RelayPushNotifier>()
                    app.koin.get<PushService>().shouldNotBeNull()
                } finally {
                    app.close()
                }
            }
        }

        test("pushModule binds NoOpPushNotifier when no relay URL is configured") {
            withSqlDatabase {
                val app =
                    koinApplication {
                        modules(
                            module {
                                single<ListenUpDatabase> { sql }
                                single<Clock> { Clock.System }
                                single { ServerSettingsRepository(get<ListenUpDatabase>(), RegistrationPolicy.CLOSED) }
                                single { PushConfig(relayUrl = null) }
                            },
                            pushModule(),
                        )
                    }
                try {
                    app.koin.get<PushNotifier>().shouldBeInstanceOf<NoOpPushNotifier>()
                    app.koin.get<PushService>().shouldNotBeNull()
                } finally {
                    app.close()
                }
            }
        }
    })
