package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.server.backup.MaintenanceState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

/**
 * Verifies that [installMaintenanceGate] genuinely short-circuits the pipeline:
 * - Gate off: route handler runs, 200.
 * - Gate on: 503 with typed BACKUP_RESTORE_IN_PROGRESS body; handler skipped.
 * - Gate off: route handler runs again, 200.
 * - Allowlist: paths under /api/rpc/ reach their handler even while the gate is active.
 */
class MaintenanceGateTest :
    FunSpec({
        test("returns 503 with RestoreInProgress code when maintenance is active, 200 otherwise") {
            val state = MaintenanceState()
            testApplication {
                application {
                    installMaintenanceGate(state)
                    routing {
                        get("/ping") { call.respondText("pong") }
                    }
                }

                // Gate off: handler runs normally.
                client.get("/ping").status shouldBe HttpStatusCode.OK

                // Gate on: 503 with the typed error discriminator in the body.
                // The wire format is {"type":"BackupError.RestoreInProgress",...}; the
                // BACKUP_RESTORE_IN_PROGRESS value is the `code` constant but is a body
                // property not a constructor param, so the @SerialName discriminator is
                // what the plan's assertion should match.
                state.enter()
                val blocked = client.get("/ping")
                blocked.status shouldBe HttpStatusCode.ServiceUnavailable
                blocked.bodyAsText().contains("BackupError.RestoreInProgress") shouldBe true

                // Gate off: handler runs again (prove the gate is reversible).
                state.exit()
                client.get("/ping").status shouldBe HttpStatusCode.OK
            }
        }

        test("allowlisted path under /api/rpc is reachable even when gate is active") {
            val state = MaintenanceState()
            testApplication {
                application {
                    installMaintenanceGate(state)
                    routing {
                        get("/api/rpc/ping") { call.respondText("rpc-pong") }
                    }
                }

                state.enter()

                // RPC mount is on the allowlist: gate must not block it.
                val response = client.get("/api/rpc/ping")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "rpc-pong"
            }
        }
    })
