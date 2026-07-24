package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString

/**
 * Round-trips [ServerInfo] through [contractJson].
 *
 * [ServerInfo] is the payload the client fetches on first connect to verify a
 * server and route the onboarding flow. The verify path reads exactly two of its
 * fields — [ServerInfo.setupRequired] (NeedsSetup vs NeedsLogin) and
 * [ServerInfo.registrationPolicy] (whether to offer "Create Account") — so this
 * test pins that both survive the wire intact. Field-name drift on either side
 * would otherwise surface only as a failed server-verification on screen one.
 */
class InstanceContractTest :
    FunSpec({

        test("ServerInfo round-trips with all fields populated") {
            val original =
                ServerInfo(
                    name = "ListenUp",
                    version = "0.0.1",
                    apiVersion = "v1",
                    setupRequired = true,
                    registrationPolicy = RegistrationPolicy.OPEN,
                    instanceId = "test-instance",
                )
            roundTrip(original) shouldBe original
        }

        test("ServerInfo preserves setupRequired and registrationPolicy across the wire") {
            val decoded =
                roundTrip(
                    ServerInfo(
                        name = "ListenUp",
                        version = "0.0.1",
                        apiVersion = "v1",
                        setupRequired = false,
                        registrationPolicy = RegistrationPolicy.APPROVAL_QUEUE,
                        instanceId = "test-instance",
                    ),
                )
            decoded.setupRequired shouldBe false
            decoded.registrationPolicy shouldBe RegistrationPolicy.APPROVAL_QUEUE
        }
    })

private inline fun <reified T : Any> roundTrip(value: T): T = contractJson.decodeFromString<T>(contractJson.encodeToString(value))
