package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.server.auth.RegistrationPolicyBroadcaster
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.koin.ktor.ext.inject

/**
 * Pins the live registration-policy stream: it is mounted OUTSIDE the JWT wall (a client on the
 * login screen has no token) and emits the exact client wire shape — a data-only [RegistrationPolicy]
 * JSON frame — the current policy on connect, then every change.
 *
 * The connection is established with NO `bearerAuth`; a 401 here would mean the route was
 * accidentally placed inside `authenticate(JWT_PROVIDER)` and is a regression.
 */
class RegistrationPolicyRoutesTest :
    FunSpec({

        test("registration-policy stream emits the current policy then a live change, unauthenticated") {
            testApplication {
                useIsolatedTestConfig(registrationPolicy = "OPEN")
                application { module() }
                val broadcaster by application.inject<RegistrationPolicyBroadcaster>()
                val client = createClient { install(SSE) }

                client.sse("/api/v1/auth/registration-policy/stream") {
                    // Heartbeat comment-events carry null data; filter to the real policy frames.
                    // The first frame is the persisted current policy (OPEN); pushing a change then
                    // yields the CLOSED frame.
                    val policies =
                        incoming
                            .filter { it.data != null }
                            .map { contractJson.decodeFromString<RegistrationPolicy>(it.data!!) }
                            .onEach { policy ->
                                if (policy == RegistrationPolicy.OPEN) broadcaster.notify(RegistrationPolicy.CLOSED)
                            }.take(2)
                            .toList()

                    policies shouldBe listOf(RegistrationPolicy.OPEN, RegistrationPolicy.CLOSED)
                }
            }
        }

        test("registration-policy stream reports CLOSED on connect when registration is already closed") {
            testApplication {
                useIsolatedTestConfig(registrationPolicy = "CLOSED")
                application { module() }
                val client = createClient { install(SSE) }

                client.sse("/api/v1/auth/registration-policy/stream") {
                    val first =
                        incoming
                            .filter { it.data != null }
                            .map { contractJson.decodeFromString<RegistrationPolicy>(it.data!!) }
                            .first()
                    first shouldBe RegistrationPolicy.CLOSED
                }
            }
        }
    })
