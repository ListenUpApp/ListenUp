package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.server.api.ServerIdentity
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.time.TimeSource

/**
 * `/healthz` is the only surface the release smoke gate (`server/scripts/serve-smoke.sh`) and a
 * container `HEALTHCHECK` can assert on, so its shape is a contract with the release pipeline —
 * not just with an operator reading it by eye.
 *
 * Three claims are load-bearing beyond "it returns 200":
 *
 *  - the body carries the literal `"status":"ok"` and `"version":"…"` text the smoke script greps
 *    for. Renaming either field silently disarms the gate rather than failing it, so those two are
 *    asserted on the raw body — the gate matches bytes, so the test matches bytes.
 *  - `schemaVersion` is populated from the real migration history, so a boot that reached
 *    `/healthz` transitively proves migrations applied.
 *  - the route is reachable with **no** credentials. A health endpoint behind auth breaks both the
 *    smoke script and any Docker healthcheck, and the mount point that guarantees it is a sibling
 *    of the `authenticate` blocks in `ApplicationRoutes` — one indentation level away from wrong.
 */
class HealthRoutesTest :
    FunSpec({

        /** The seam: the real route + the real JSON config, with the schema-version provider faked. */
        fun ApplicationTestBuilder.mountHealthRoutes(schemaVersion: String?) {
            application {
                install(ContentNegotiation) { json(contractJson) }
                routing {
                    healthRoutes(
                        schemaVersion = { schemaVersion },
                        startedAt = TimeSource.Monotonic.markNow(),
                    )
                }
            }
        }

        test("GET /healthz reports ok") {
            testApplication {
                mountHealthRoutes(schemaVersion = "67")

                val response = client.get("/healthz")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "\"status\":\"ok\""
            }
        }

        test("GET /healthz reports the build version") {
            testApplication {
                mountHealthRoutes(schemaVersion = "67")

                val body = client.get("/healthz").bodyAsText()

                body shouldContain "\"version\":\"${ServerIdentity.VERSION}\""
            }
        }

        test("GET /healthz reports uptime") {
            testApplication {
                mountHealthRoutes(schemaVersion = "67")

                val body = contractJson.decodeFromString<HealthResponse>(client.get("/healthz").bodyAsText())

                body.uptimeSeconds shouldBeGreaterThanOrEqual 0L
            }
        }

        test("GET /healthz needs no credentials and reports the applied schema version") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                // No bearer, no cookie — exactly what the smoke script and a HEALTHCHECK send.
                val response = client.get("/healthz")

                response.status shouldBe HttpStatusCode.OK
                val raw = response.bodyAsText()
                contractJson
                    .decodeFromString<HealthResponse>(raw)
                    .schemaVersion
                    .shouldNotBeNull()
                    .shouldNotBeBlank()
                // The literal shape serve-smoke.sh matches, so the gate and this test agree on
                // bytes rather than on two independent readings of "non-blank".
                raw shouldContain Regex("\"schemaVersion\":\"[^\"]+\"")
            }
        }
    })
