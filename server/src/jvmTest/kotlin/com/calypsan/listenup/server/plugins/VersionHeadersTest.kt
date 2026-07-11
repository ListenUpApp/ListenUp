package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.api.VersionHeaders
import com.calypsan.listenup.server.api.ServerIdentity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

/**
 * Verifies that [installVersionHeaders] stamps `X-Server-Version`/`X-Server-Api` on every
 * response (see [VersionHeaders]) and records an incoming `X-Client-Version` into
 * [ClientVersionMetrics] — the never-stranded counterpart to the client's outbound
 * `X-Client-*` headers (`ApiClientFactory`).
 */
class VersionHeadersTest :
    FunSpec({
        test("every response carries X-Server-Version and X-Server-Api") {
            testApplication {
                application {
                    installVersionHeaders()
                    routing {
                        get("/ping") { call.respondText("pong") }
                    }
                }

                val response = client.get("/ping")

                response.headers[VersionHeaders.SERVER_VERSION] shouldBe ServerIdentity.VERSION
                response.headers[VersionHeaders.SERVER_API] shouldBe ServerIdentity.API_VERSION
            }
        }

        test("an incoming X-Client-Version is recorded exactly once per request") {
            testApplication {
                application {
                    installVersionHeaders()
                    routing {
                        get("/ping") { call.respondText("pong") }
                    }
                }

                client.get("/ping") { header(VersionHeaders.CLIENT_VERSION, "9.9.9") }

                ClientVersionMetrics.snapshot()["9.9.9"] shouldBe 1L

                client.get("/ping") { header(VersionHeaders.CLIENT_VERSION, "9.9.9") }

                ClientVersionMetrics.snapshot()["9.9.9"] shouldBe 2L
            }
        }
    })
