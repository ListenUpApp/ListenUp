package com.calypsan.listenup.server

import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

class HealthRoutesTest :
    FunSpec({
        test("GET /healthz returns 200 with status ok") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val response = client.get("/healthz")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe """{"status":"ok"}"""
            }
        }
    })
