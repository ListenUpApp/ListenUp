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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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

        test("gate brackets non-allowlisted requests: inFlight increments around proceed()") {
            // Verify FIX B: beginRequest/endRequest wrap every non-allowlisted admitted call.
            // We observe the inFlight count from inside the route handler using a real-coroutine
            // latch (not runTest/virtual-time, since testApplication uses real dispatchers).
            val state = MaintenanceState()
            val inFlightRecorded =
                java.util.concurrent.atomic
                    .AtomicInteger(-1)
            val handlerReady = java.util.concurrent.CountDownLatch(1)
            val handlerRelease = java.util.concurrent.CountDownLatch(1)

            testApplication {
                application {
                    installMaintenanceGate(state)
                    routing {
                        get("/slow") {
                            // Record inFlight while inside the handler (after beginRequest)
                            inFlightRecorded.set(state.inFlightCount())
                            handlerReady.countDown()
                            // Block until the test lets us proceed (real blocking is fine on IO thread)
                            handlerRelease.await()
                            call.respondText("done")
                        }
                    }
                }

                // Fire the request asynchronously on the IO dispatcher so it doesn't block this scope
                coroutineScope {
                    val deferred = async(Dispatchers.IO) { client.get("/slow") }
                    // Wait until handler has recorded the inFlight value
                    handlerReady.await()
                    inFlightRecorded.get() shouldBe 1
                    // Release the handler
                    handlerRelease.countDown()
                    deferred.await()
                }

                // After the handler returned, endRequest ran → inFlight == 0
                state.inFlightCount() shouldBe 0
            }
        }

        test("drain() waits while a request is in flight then returns once it completes") {
            val state = MaintenanceState()
            val handlerReady = java.util.concurrent.CountDownLatch(1)
            val handlerRelease = java.util.concurrent.CountDownLatch(1)

            testApplication {
                application {
                    installMaintenanceGate(state)
                    routing {
                        get("/slow2") {
                            handlerReady.countDown()
                            handlerRelease.await()
                            call.respondText("done")
                        }
                    }
                }

                coroutineScope {
                    val deferred = async(Dispatchers.IO) { client.get("/slow2") }

                    // Wait for the handler to be executing (inFlight == 1)
                    handlerReady.await()

                    // drain() with a short timeout while the handler is still in-flight
                    val drainedBeforeRelease = state.drain(timeoutMs = 200, stepMs = 20)
                    drainedBeforeRelease shouldBe false

                    // Release handler → endRequest runs → inFlight drops to 0
                    handlerRelease.countDown()
                    deferred.await()
                }

                // drain() now completes immediately
                val drainedAfterRelease = state.drain(timeoutMs = 1_000, stepMs = 20)
                drainedAfterRelease shouldBe true
            }
        }
    })
