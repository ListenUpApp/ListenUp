package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.DomainList
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode

class SyncDomainListRouteTest :
    FunSpec({

        test("GET /api/v1/sync/domains returns sorted registered domain names") {
            withTestApplication {
                val response = client.get("/api/v1/sync/domains")
                response.status shouldBe HttpStatusCode.OK
                val list: DomainList = response.body()
                list.domains shouldBe listOf("tags") // only Tags registered in this phase
            }
        }
    })
