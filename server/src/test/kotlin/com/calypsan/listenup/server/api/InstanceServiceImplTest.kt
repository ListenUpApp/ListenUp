package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class InstanceServiceImplTest :
    FunSpec({
        test("getServerInfo name reflects the stored server_name, default otherwise") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val settings = ServerSettingsRepository(db, default = RegistrationPolicy.OPEN)
                    val svc = InstanceServiceImpl(db, settings)
                    (svc.getServerInfo() as AppResult.Success).data.name shouldBe ServerIdentity.NAME
                    settings.setServerName("Renamed")
                    (svc.getServerInfo() as AppResult.Success).data.name shouldBe "Renamed"
                }
            }
        }
    })
