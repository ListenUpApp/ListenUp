package com.calypsan.listenup.server.mdns

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.coroutines.test.runTest

class InstanceIdentityTest :
    FunSpec({
        test("instanceId generates and persists a stable id, returning the same value on subsequent calls") {
            withInMemoryDatabase {
                val settings = ServerSettingsRepository(db = this, default = RegistrationPolicy.CLOSED)
                val identity = InstanceIdentity(settings)
                runTest {
                    val first = identity.instanceId()
                    first.shouldNotBeBlank()
                    val second = identity.instanceId()
                    second shouldBe first
                }
            }
        }

        test("instanceId returns the already-persisted id without overwriting it") {
            withInMemoryDatabase {
                val settings = ServerSettingsRepository(db = this, default = RegistrationPolicy.CLOSED)
                runTest {
                    settings.setValue("instance_id", "preexisting-id")
                    InstanceIdentity(settings).instanceId() shouldBe "preexisting-id"
                }
            }
        }
    })
