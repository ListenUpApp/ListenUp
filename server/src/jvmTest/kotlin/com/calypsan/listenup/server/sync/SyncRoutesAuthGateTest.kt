package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

class SyncRoutesAuthGateTest :
    FunSpec({

        test("GET /api/v1/sync/domains without bearer token returns 401") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val r: HttpResponse = client.get("/api/v1/sync/domains")
                r.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("GET /api/v1/sync/tags?since=0 without bearer token returns 401") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val r: HttpResponse = client.get("/api/v1/sync/tags?since=0")
                r.status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
