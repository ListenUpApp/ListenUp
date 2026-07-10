package com.calypsan.listenup.server.settings

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.server.api.ServerIdentity
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class ServerSettingsRepositoryTest :
    FunSpec({
        test("serverName defaults to ServerIdentity.NAME when unset, then round-trips") {
            withSqlDatabase {
                val repo = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                runTest {
                    repo.serverName() shouldBe ServerIdentity.NAME
                    repo.setServerName("My Library")
                    repo.serverName() shouldBe "My Library"
                }
            }
        }
        test("remoteUrl is null when unset, round-trips, and empty string clears it") {
            withSqlDatabase {
                val repo = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                runTest {
                    repo.remoteUrl() shouldBe null
                    repo.setRemoteUrl("https://example.com")
                    repo.remoteUrl() shouldBe "https://example.com"
                    repo.setRemoteUrl("")
                    repo.remoteUrl() shouldBe null
                }
            }
        }
        test("pushNotificationsEnabled defaults to true when unset, then round-trips") {
            withSqlDatabase {
                val repo = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                runTest {
                    repo.pushNotificationsEnabled() shouldBe true
                    repo.setPushNotificationsEnabled(false)
                    repo.pushNotificationsEnabled() shouldBe false
                    repo.setPushNotificationsEnabled(true)
                    repo.pushNotificationsEnabled() shouldBe true
                }
            }
        }
    })
