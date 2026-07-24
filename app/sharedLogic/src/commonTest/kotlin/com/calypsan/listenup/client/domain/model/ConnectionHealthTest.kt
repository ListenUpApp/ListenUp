package com.calypsan.listenup.client.domain.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ConnectionHealthTest :
    FunSpec({
        test("Unreachable and Outdated carry their data; the objects are singletons") {
            (ConnectionHealth.Healthy === ConnectionHealth.Healthy) shouldBe true
            (ConnectionHealth.SessionExpired === ConnectionHealth.SessionExpired) shouldBe true
            ConnectionHealth.Unreachable(sinceMillis = 42L).sinceMillis shouldBe 42L
            val outdated = ConnectionHealth.Outdated(clientVersion = "0.6.0", serverVersion = "0.7.0")
            outdated.clientVersion shouldBe "0.6.0"
            outdated.serverVersion shouldBe "0.7.0"
        }
    })
