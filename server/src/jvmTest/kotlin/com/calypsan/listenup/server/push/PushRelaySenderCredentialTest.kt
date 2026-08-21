package com.calypsan.listenup.server.push

import com.calypsan.listenup.api.contractJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * The sender credential the relay will soon require (`PROTOCOL.md` — "Sender credential").
 *
 * The relay's rollout is two-phase: today an ABSENT credential is accepted and only a WRONG one is
 * rejected; phase 2 makes it mandatory. Push is best-effort and swallows its own failures, so a
 * server that never learned to send this would start 401-ing on every notification the day that
 * switch flips — with no error surfaced anywhere and nothing in the UI to notice. These tests pin
 * both halves of getting that right.
 */
class PushRelaySenderCredentialTest :
    FunSpec({

        val emptyPayload = contractJson.parseToJsonElement("{}")

        fun clientFor(
            engine: MockEngine,
            senderToken: String?,
        ) = PushRelayClient(
            relayUrl = "https://relay.example.com",
            http = HttpClient(engine) { install(ContentNegotiation) { json(contractJson) } },
            senderToken = senderToken,
        )

        fun okEngine(captured: MutableList<String?>) =
            MockEngine { request ->
                captured += request.headers[HttpHeaders.Authorization]
                respond(
                    content = """{"results":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }

        test("a configured credential is sent as a bearer token") {
            runTest {
                val seen = mutableListOf<String?>()
                clientFor(okEngine(seen), senderToken = "s3cret")
                    .send(tokens = emptyList(), payloadJson = emptyPayload, collapseKey = null)

                seen.single() shouldBe "Bearer s3cret"
            }
        }

        // ⛔ Absent, not empty. The relay rejects a PRESENT-but-wrong credential and accepts an
        // absent one, so sending `Bearer ` for an unconfigured server would convert a working push
        // into a 401 — turning the migration window's whole purpose inside out.
        test("no credential configured sends no header at all") {
            runTest {
                val seen = mutableListOf<String?>()
                clientFor(okEngine(seen), senderToken = null)
                    .send(tokens = emptyList(), payloadJson = emptyPayload, collapseKey = null)

                seen.single().shouldBeNull()
            }
        }

        test("a blank credential is treated as absent, not sent empty") {
            runTest {
                val seen = mutableListOf<String?>()
                clientFor(okEngine(seen), senderToken = "   ")
                    .send(tokens = emptyList(), payloadJson = emptyPayload, collapseKey = null)

                seen.single().shouldBeNull()
            }
        }
        // ⛔ The failure mode this whole credential exists to avoid becoming invisible. Push
        // swallows its own errors by design, so a refused credential would otherwise be a
        // permanent, silent outage: no notification ever arrives and nothing anywhere says why.
        test("a refused credential surfaces as a distinct, actionable failure") {
            runTest {
                val engine =
                    MockEngine {
                        respond(
                            content = """{"error":"invalid sender credential"}""",
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    }

                shouldThrow<RelaySenderCredentialRejected> {
                    clientFor(engine, senderToken = "wrong")
                        .send(tokens = emptyList(), payloadJson = emptyPayload, collapseKey = null)
                }
            }
        }

        // A 5xx must NOT be mistaken for a credential problem — it is transient and retryable, and
        // telling an operator to fix their token would send them chasing the wrong thing.
        test("a server error is not reported as a credential problem") {
            runTest {
                val engine =
                    MockEngine {
                        respond(
                            content = "upstream exploded",
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf("Content-Type", ContentType.Text.Plain.toString()),
                        )
                    }

                val thrown =
                    shouldThrowAny {
                        clientFor(engine, senderToken = "s3cret")
                            .send(tokens = emptyList(), payloadJson = emptyPayload, collapseKey = null)
                    }
                (thrown is RelaySenderCredentialRejected) shouldBe false
            }
        }
    })
