package com.calypsan.listenup.server

import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

class WebShellRoutesTest :
    FunSpec({
        test("GET / serves the web shell HTML") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val response = client.get("/")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "ListenUp"
            }
        }
    })
