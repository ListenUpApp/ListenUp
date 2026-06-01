package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.server.auth.RegistrationBroadcaster
import com.calypsan.listenup.server.auth.RegistrationDecision
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.koin.ktor.ext.inject

/**
 * Pins the multi-user MC §Task-3 contract: the registration-status SSE stream is mounted
 * OUTSIDE the JWT wall (a registrant awaiting approval has no token yet) and emits the exact
 * client wire shape — a data-only `RegistrationStatusEvent` JSON frame whose `status` is
 * `"pending"` on connect, then the broadcaster-driven terminal decision (`"approved"`/`"denied"`).
 *
 * The connection is established with NO `bearerAuth`; a 401 here would mean the route was
 * accidentally placed inside `authenticate(JWT_PROVIDER)` and is a regression.
 */
class RegistrationStatusRoutesTest :
    FunSpec({

        test("registration-status stream emits pending then the decision, unauthenticated") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val broadcaster by application.inject<RegistrationBroadcaster>()
                val client =
                    createClient {
                        install(SSE)
                    }
                client.sse("/api/v1/auth/registration-status/u1/stream") {
                    // Heartbeat comment-events carry null data; filter to the real status frames.
                    // The first data frame is "pending"; firing the decision then yields the
                    // terminal "approved" frame, after which the server closes the stream.
                    val statuses =
                        incoming
                            .filter { it.data != null }
                            .map { contractJson.decodeFromString<RegistrationStatusEvent>(it.data!!).status }
                            .onEach { status ->
                                if (status == "pending") broadcaster.notify("u1", RegistrationDecision.Approved)
                            }.take(2)
                            .toList()

                    statuses shouldBe listOf("pending", "approved")
                }
            }
        }
    })
