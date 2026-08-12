@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.RootResetToken
import com.calypsan.listenup.server.mdns.InstanceIdentity
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.push.PushConfig
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
private class MutableClock(
    var current: Instant,
) : Clock {
    override fun now(): Instant = current
}

private val CONFIGURED_PUSH = PushConfig(relayUrl = "https://push.example.com")

/** Builds the service under test; defaults model the common disarmed-hatch case. */
@OptIn(ExperimentalTime::class)
private fun instanceService(
    sql: ListenUpDatabase,
    settings: ServerSettingsRepository,
    push: PushConfig = CONFIGURED_PUSH,
    token: RootResetToken = RootResetToken.disarmed(),
    clock: Clock = Clock.System,
) = InstanceServiceImpl(sql, settings, InstanceIdentity(settings), push, token, clock)

class InstanceServiceImplTest :
    FunSpec({
        test("getServerInfo name reflects the stored server_name, default otherwise") {
            withSqlDatabase {
                runTest {
                    val settings = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                    val svc = instanceService(sql, settings)
                    (svc.getServerInfo() as AppResult.Success).data.name shouldBe ServerIdentity.NAME
                    settings.setServerName("Renamed")
                    (svc.getServerInfo() as AppResult.Success).data.name shouldBe "Renamed"
                }
            }
        }

        test("getServerInfo returns the operator-set remote URL") {
            withSqlDatabase {
                runTest {
                    val settings = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                    val svc = instanceService(sql, settings)
                    settings.setValue("remote_url", "https://library.example.com")
                    (svc.getServerInfo() as AppResult.Success).data.remoteUrl shouldBe "https://library.example.com"
                }
            }
        }

        test("getServerInfo returns null remoteUrl when unset") {
            withSqlDatabase {
                runTest {
                    val settings = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                    val svc = instanceService(sql, settings)
                    (svc.getServerInfo() as AppResult.Success).data.remoteUrl shouldBe null
                }
            }
        }

        test("getServerInfo returns the persisted instanceId, stable across instances") {
            withSqlDatabase {
                runTest {
                    val settings = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                    val identity = InstanceIdentity(settings)
                    val expectedId = identity.instanceId()

                    val service = instanceService(sql, settings)
                    val info = (service.getServerInfo() as AppResult.Success).data
                    info.instanceId shouldBe expectedId

                    val service2 = instanceService(sql, settings)
                    val info2 = (service2.getServerInfo() as AppResult.Success).data
                    info2.instanceId shouldBe expectedId
                }
            }
        }

        test("rootResetArmed is false when the hatch is disarmed") {
            withSqlDatabase {
                runTest {
                    val settings = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                    val svc =
                        InstanceServiceImpl(
                            sql,
                            settings,
                            InstanceIdentity(settings),
                            CONFIGURED_PUSH,
                            RootResetToken.disarmed(),
                            Clock.System,
                        )
                    (svc.getServerInfo() as AppResult.Success).data.rootResetArmed shouldBe false
                }
            }
        }

        test("rootResetArmed is true while armed, then false after the window closes") {
            withSqlDatabase {
                runTest {
                    val settings = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                    val clock = MutableClock(Instant.fromEpochMilliseconds(0))
                    val token = RootResetToken.armed(clock)
                    val svc =
                        instanceService(sql, settings, token = token, clock = clock)

                    (svc.getServerInfo() as AppResult.Success).data.rootResetArmed shouldBe true

                    clock.current = Instant.fromEpochMilliseconds(RootResetToken.WINDOW.inWholeMilliseconds + 1)
                    (svc.getServerInfo() as AppResult.Success).data.rootResetArmed shouldBe false
                }
            }
        }

        test("rootResetArmed goes false once the one-time token is consumed") {
            withSqlDatabase {
                runTest {
                    val settings = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                    val clock = MutableClock(Instant.fromEpochMilliseconds(0))
                    val token = RootResetToken.armed(clock)
                    token.consume(token.token, clock.now())
                    val svc =
                        instanceService(sql, settings, token = token, clock = clock)

                    (svc.getServerInfo() as AppResult.Success).data.rootResetArmed shouldBe false
                }
            }
        }

        test("pushEnabled requires BOTH the admin toggle and a relay URL") {
            withSqlDatabase {
                runTest {
                    val settingsOn = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                    settingsOn.setPushNotificationsEnabled(true)
                    val svcOnConfigured = instanceService(sql, settingsOn)
                    (svcOnConfigured.getServerInfo() as AppResult.Success).data.pushEnabled shouldBe true

                    val settingsOff = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                    settingsOff.setPushNotificationsEnabled(false)
                    val svcOffConfigured =
                        instanceService(sql, settingsOff)
                    (svcOffConfigured.getServerInfo() as AppResult.Success).data.pushEnabled shouldBe false

                    val settingsOnUnconfigured = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                    settingsOnUnconfigured.setPushNotificationsEnabled(true)
                    val svcOnUnconfigured =
                        InstanceServiceImpl(
                            sql,
                            settingsOnUnconfigured,
                            InstanceIdentity(settingsOnUnconfigured),
                            PushConfig(relayUrl = null),
                            RootResetToken.disarmed(),
                            Clock.System,
                        )
                    (svcOnUnconfigured.getServerInfo() as AppResult.Success).data.pushEnabled shouldBe false
                }
            }
        }
    })
