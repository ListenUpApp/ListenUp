package com.calypsan.listenup.server.hardcover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")

private fun graphQlClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
    HardcoverGraphQlClient(http = HttpClient(MockEngine(handler)), apiBaseUrl = "https://hc.test")

private fun HttpRequestData.bodyText(): String = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

/** `me` resolves who a Hardcover token belongs to, and folds every failure into a typed result. */
class HardcoverGraphQlClientTest :
    FunSpec({

        test("me posts the query with a Bearer token and returns the first me entry") {
            runTest {
                var sent: HttpRequestData? = null
                var sentBody = ""
                val client =
                    graphQlClient { req ->
                        sent = req
                        sentBody = req.bodyText()
                        respond("""{"data":{"me":[{"id":42,"username":"simon"}]}}""", HttpStatusCode.OK, JSON_HEADERS)
                    }

                client.me("hc_at_1") shouldBe MeResult.Found(HardcoverMe(42, "simon"))

                val req = sent!!
                req.method shouldBe HttpMethod.Post
                req.url.toString() shouldBe "https://hc.test/v1/graphql"
                req.headers[HttpHeaders.Authorization] shouldBe "Bearer hc_at_1"
                req.headers[HttpHeaders.UserAgent]!! shouldStartWith "ListenUp"
                Json
                    .parseToJsonElement(sentBody)
                    .jsonObject["query"]!!
                    .jsonPrimitive.content shouldBe "{ me { id username } }"
            }
        }

        test("a 401 is Unauthorized") {
            runTest {
                val client = graphQlClient { respond("", HttpStatusCode.Unauthorized) }
                client.me("hc_at_bad") shouldBe MeResult.Unauthorized
            }
        }

        test("a 500 is Unavailable") {
            runTest {
                val client = graphQlClient { respond("oops", HttpStatusCode.InternalServerError) }
                client.me("hc_at_1").shouldBeInstanceOf<MeResult.Unavailable>()
            }
        }

        test("an empty me list is Unavailable") {
            runTest {
                val client = graphQlClient { respond("""{"data":{"me":[]}}""", HttpStatusCode.OK, JSON_HEADERS) }
                client.me("hc_at_1").shouldBeInstanceOf<MeResult.Unavailable>()
            }
        }

        test("an unreachable Hardcover is Unavailable, not a crash") {
            runTest {
                val client = graphQlClient { throw IOException("connection refused") }
                client.me("hc_at_1").shouldBeInstanceOf<MeResult.Unavailable>()
            }
        }
    })
