package com.calypsan.listenup.server

import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

/**
 * Phase 1A served `GET /` here. From Phase 1B `/` requires a loopback REST round-trip
 * (ServerInfo), which the in-memory `testApplication` transport can't satisfy — that flow
 * is covered by the real-port `WebAuthEndToEndTest`. These assertions keep the static-asset
 * surface honest (no loopback needed).
 */
class WebShellRoutesTest :
    FunSpec({
        test("GET /assets/htmx.min.js serves the vendored htmx runtime") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                client.get("/assets/htmx.min.js").status shouldBe HttpStatusCode.OK
            }
        }

        test("GET /assets/app.css serves generated Tailwind utilities") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                client.get("/assets/app.css").status shouldBe HttpStatusCode.OK
            }
        }
    })
