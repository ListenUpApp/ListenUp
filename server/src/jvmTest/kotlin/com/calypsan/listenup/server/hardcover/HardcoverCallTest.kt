package com.calypsan.listenup.server.hardcover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import java.io.IOException

private fun clientAnswering(
    status: HttpStatusCode,
    body: String = "{}",
    vararg headers: Pair<String, String>,
) = HardcoverGraphQlClient(
    http =
        HttpClient(
            MockEngine {
                respond(
                    body,
                    status,
                    headersOf(
                        *(
                            headers.map { it.first to listOf(it.second) } +
                                (HttpHeaders.ContentType to listOf("application/json"))
                        ).toTypedArray(),
                    ),
                )
            },
        ),
    apiBaseUrl = "https://hc.test",
)

private suspend fun HardcoverGraphQlClient.probe(): HardcoverCall<String> =
    call("hc_at_1", "{ me { id } }", JsonObject(emptyMap()), "probe")

/** Every answer Hardcover can give is classified the way the push worker's error policy reads it. */
class HardcoverCallTest :
    FunSpec({

        test("a 200 with data is Ok with the body") {
            runTest {
                clientAnswering(HttpStatusCode.OK, """{"data":{"me":[]}}""").probe() shouldBe HardcoverCall.Ok("""{"data":{"me":[]}}""")
            }
        }

        test("a 200 carrying a GraphQL errors array is Failed, naming the first error") {
            runTest {
                clientAnswering(HttpStatusCode.OK, """{"errors":[{"message":"field 'nope' not found"}]}""").probe() shouldBe
                    HardcoverCall.Failed("probe: field 'nope' not found")
            }
        }

        test("401 invalid_token is Unauthorized") {
            runTest {
                clientAnswering(HttpStatusCode.Unauthorized, """{"error":"invalid_token"}""").probe() shouldBe
                    HardcoverCall.Unauthorized
            }
        }

        test("403 insufficient_scope in the body is MissingScope with the scope") {
            runTest {
                clientAnswering(HttpStatusCode.Forbidden, """{"error":"insufficient_scope","scope":"write:library"}""").probe() shouldBe
                    HardcoverCall.MissingScope("write:library")
            }
        }

        test("403 insufficient_scope in the WWW-Authenticate challenge is MissingScope too") {
            runTest {
                clientAnswering(
                    HttpStatusCode.Forbidden,
                    "",
                    HttpHeaders.WWWAuthenticate to """Bearer error="insufficient_scope", scope="write:library"""",
                ).probe() shouldBe HardcoverCall.MissingScope("write:library")
            }
        }

        test("403 unsupported_operation and top_level_limit_exceeded are Failed, not a scope problem") {
            runTest {
                clientAnswering(HttpStatusCode.Forbidden, """{"error":"unsupported_operation"}""").probe() shouldBe
                    HardcoverCall.Failed("probe 403 unsupported_operation")
                clientAnswering(HttpStatusCode.Forbidden, """{"error":"top_level_limit_exceeded"}""").probe() shouldBe
                    HardcoverCall.Failed("probe 403 top_level_limit_exceeded")
            }
        }

        test("429 is Throttled and honours Retry-After, in milliseconds") {
            runTest {
                clientAnswering(HttpStatusCode.TooManyRequests, "{}", HttpHeaders.RetryAfter to "120").probe() shouldBe
                    HardcoverCall.Throttled(retryAfterMs = 120_000L)
            }
        }

        test("503 is Throttled, with no hint when Hardcover sends none") {
            runTest { clientAnswering(HttpStatusCode.ServiceUnavailable).probe() shouldBe HardcoverCall.Throttled(retryAfterMs = null) }
        }

        test("408 and 500 are Failed with the status") {
            runTest {
                clientAnswering(HttpStatusCode.RequestTimeout).probe() shouldBe HardcoverCall.Failed("probe 408")
                clientAnswering(HttpStatusCode.InternalServerError).probe() shouldBe HardcoverCall.Failed("probe 500")
            }
        }

        test("an unreachable Hardcover is Failed, never a crash") {
            runTest {
                val client =
                    HardcoverGraphQlClient(HttpClient(MockEngine { throw IOException("connection refused") }), "https://hc.test")
                client.probe().shouldBeInstanceOf<HardcoverCall.Failed>()
            }
        }

        test("map transforms Ok and passes every failure through") {
            HardcoverCall.Ok(2).map { it * 2 } shouldBe HardcoverCall.Ok(4)
            HardcoverCall.Throttled(5L).map { 1 } shouldBe HardcoverCall.Throttled(5L)
            HardcoverCall.Unauthorized.map { 1 } shouldBe HardcoverCall.Unauthorized
        }
    })
