package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SyncDomainListPullTest :
    FunSpec({

        test("listDomains() returns sorted registered domain names") {
            withTestApplication {
                val domains = syncService().listDomains().shouldSucceed()
                domains shouldBe listOf("tags") // only Tags registered in this phase
            }
        }
    })
