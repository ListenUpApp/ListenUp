package com.calypsan.listenup.server.settings

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.server.api.ServerIdentity
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class ServerSettingsRepositoryTest :
    FunSpec({
        test("serverName defaults to ServerIdentity.NAME when unset, then round-trips") {
            withInMemoryDatabase {
                val repo = ServerSettingsRepository(this, default = RegistrationPolicy.OPEN)
                runTest {
                    repo.serverName() shouldBe ServerIdentity.NAME
                    repo.setServerName("My Library")
                    repo.serverName() shouldBe "My Library"
                }
            }
        }
        test("remoteUrl is null when unset, round-trips, and empty string clears it") {
            withInMemoryDatabase {
                val repo = ServerSettingsRepository(this, default = RegistrationPolicy.OPEN)
                runTest {
                    repo.remoteUrl() shouldBe null
                    repo.setRemoteUrl("https://example.com")
                    repo.remoteUrl() shouldBe "https://example.com"
                    repo.setRemoteUrl("")
                    repo.remoteUrl() shouldBe null
                }
            }
        }
    })
