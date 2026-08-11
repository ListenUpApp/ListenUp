package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.mdns.InstanceIdentity
import com.calypsan.listenup.server.push.PushConfig
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private val CONFIGURED_PUSH = PushConfig(relayUrl = "https://push.example.com")

class InstanceServiceImplTest :
    FunSpec({
        test("getServerInfo name reflects the stored server_name, default otherwise") {
            withSqlDatabase {
                runTest {
                    val settings = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                    val svc = InstanceServiceImpl(sql, settings, InstanceIdentity(settings), CONFIGURED_PUSH)
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
                    val svc = InstanceServiceImpl(sql, settings, InstanceIdentity(settings), CONFIGURED_PUSH)
                    settings.setValue("remote_url", "https://library.example.com")
                    (svc.getServerInfo() as AppResult.Success).data.remoteUrl shouldBe "https://library.example.com"
                }
            }
        }

        test("getServerInfo returns null remoteUrl when unset") {
            withSqlDatabase {
                runTest {
                    val settings = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                    val svc = InstanceServiceImpl(sql, settings, InstanceIdentity(settings), CONFIGURED_PUSH)
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

                    val service = InstanceServiceImpl(sql, settings, InstanceIdentity(settings), CONFIGURED_PUSH)
                    val info = (service.getServerInfo() as AppResult.Success).data
                    info.instanceId shouldBe expectedId

                    val service2 = InstanceServiceImpl(sql, settings, InstanceIdentity(settings), CONFIGURED_PUSH)
                    val info2 = (service2.getServerInfo() as AppResult.Success).data
                    info2.instanceId shouldBe expectedId
                }
            }
        }

        test("pushEnabled requires BOTH the admin toggle and a relay URL") {
            withSqlDatabase {
                runTest {
                    val settingsOn = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                    settingsOn.setPushNotificationsEnabled(true)
                    val svcOnConfigured = InstanceServiceImpl(sql, settingsOn, InstanceIdentity(settingsOn), CONFIGURED_PUSH)
                    (svcOnConfigured.getServerInfo() as AppResult.Success).data.pushEnabled shouldBe true

                    val settingsOff = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                    settingsOff.setPushNotificationsEnabled(false)
                    val svcOffConfigured =
                        InstanceServiceImpl(sql, settingsOff, InstanceIdentity(settingsOff), CONFIGURED_PUSH)
                    (svcOffConfigured.getServerInfo() as AppResult.Success).data.pushEnabled shouldBe false

                    val settingsOnUnconfigured = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                    settingsOnUnconfigured.setPushNotificationsEnabled(true)
                    val svcOnUnconfigured =
                        InstanceServiceImpl(
                            sql,
                            settingsOnUnconfigured,
                            InstanceIdentity(settingsOnUnconfigured),
                            PushConfig(relayUrl = null),
                        )
                    (svcOnUnconfigured.getServerInfo() as AppResult.Success).data.pushEnabled shouldBe false
                }
            }
        }
    })
