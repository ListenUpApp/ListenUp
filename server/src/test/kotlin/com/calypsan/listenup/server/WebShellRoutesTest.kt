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
        test("GET / serves the web shell HTML with asset links") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val response = client.get("/")
                response.status shouldBe HttpStatusCode.OK
                val html = response.bodyAsText()
                html shouldContain "<!DOCTYPE html>"
                html shouldContain "ListenUp"
                html shouldContain "/assets/htmx.min.js"
                html shouldContain "/assets/app.css"
            }
        }

        test("GET /assets/htmx.min.js serves the vendored htmx runtime") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val response = client.get("/assets/htmx.min.js")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "htmx"
            }
        }

        test("GET /assets/app.css serves generated Tailwind utilities") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val response = client.get("/assets/app.css")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain ".mx-auto"
            }
        }
    })
