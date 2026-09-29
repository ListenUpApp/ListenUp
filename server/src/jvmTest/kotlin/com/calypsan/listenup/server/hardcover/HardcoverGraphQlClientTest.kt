package com.calypsan.listenup.server.hardcover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldNotBe
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

private fun fixture(name: String): String =
    checkNotNull(HardcoverGraphQlClientTest::class.java.getResource("/hardcover/$name")) { "missing fixture $name" }.readText()

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

        test("editionRatingByAsin finds the book's average, count, title and author, sending the asin as a variable") {
            runTest {
                var sentBody = ""
                val client =
                    graphQlClient { req ->
                        sentBody = req.bodyText()
                        respond(fixture("rating-by-asin-found.json"), HttpStatusCode.OK, JSON_HEADERS)
                    }

                val result = client.editionRatingByAsin("hc_at_1", "B08G9RZBTT").shouldBeInstanceOf<HardcoverRatingResult.Found>()

                result.average shouldBe (4.507 plusOrMinus 0.001)
                result.count shouldBe 8107
                result.title shouldBe "Project Hail Mary"
                result.author shouldBe "Andy Weir"
                Json
                    .parseToJsonElement(sentBody)
                    .jsonObject["variables"]!!
                    .jsonObject["asin"]!!
                    .jsonPrimitive.content shouldBe "B08G9RZBTT"
            }
        }

        test("editionRatingByIsbn finds the book") {
            runTest {
                val client = graphQlClient { respond(fixture("rating-by-isbn-found.json"), HttpStatusCode.OK, JSON_HEADERS) }
                val result = client.editionRatingByIsbn("hc_at_1", "9780593135204")
                result.shouldBeInstanceOf<HardcoverRatingResult.Found>().count shouldBe 8107
            }
        }

        test("an unknown asin is NotFound") {
            runTest {
                val client = graphQlClient { respond(fixture("rating-by-asin-missing.json"), HttpStatusCode.OK, JSON_HEADERS) }
                client.editionRatingByAsin("hc_at_1", "B072HRZ7LD") shouldBe HardcoverRatingResult.NotFound
            }
        }

        test("a book with a null rating or no ratings is NotFound") {
            runTest {
                val unrated = """{"data":{"editions":[{"book":{"id":1,"title":"T","rating":null,"ratings_count":0,"contributions":[]}}]}}"""
                val client = graphQlClient { respond(unrated, HttpStatusCode.OK, JSON_HEADERS) }
                client.editionRatingByAsin("hc_at_1", "X") shouldBe HardcoverRatingResult.NotFound
            }
        }

        test("rating lookups fold 401 to Unauthorized, 500 and network failure to Unavailable") {
            runTest {
                graphQlClient { respond("", HttpStatusCode.Unauthorized) }
                    .editionRatingByAsin("bad", "X") shouldBe HardcoverRatingResult.Unauthorized
                graphQlClient { respond("oops", HttpStatusCode.InternalServerError) }
                    .editionRatingByIsbn("t", "X")
                    .shouldBeInstanceOf<HardcoverRatingResult.Unavailable>()
                graphQlClient { throw IOException("refused") }
                    .editionRatingByAsin("t", "X")
                    .shouldBeInstanceOf<HardcoverRatingResult.Unavailable>()
            }
        }

        test("booksByTitle returns the candidates with their authors, unrated ones included") {
            runTest {
                val client = graphQlClient { respond(fixture("rating-by-title-author.json"), HttpStatusCode.OK, JSON_HEADERS) }

                val result =
                    client
                        .booksByTitle("hc_at_1", "The Best Christmas Pageant Ever")
                        .shouldBeInstanceOf<HardcoverCandidatesResult.Found>()

                result.candidates.size shouldBe 4
                result.candidates.first().author shouldBe "Barbara Robinson"
                result.candidates.first().count shouldBe 58
                result.candidates.first().average!! shouldBe (4.259 plusOrMinus 0.001)
                result.candidates.last().author shouldBe null
                result.candidates.last().average shouldBe null
                result.candidates.first() shouldNotBe null
            }
        }

        test("booksByTitle folds a 401 to Unauthorized") {
            runTest {
                graphQlClient { respond("", HttpStatusCode.Unauthorized) }
                    .booksByTitle("bad", "T") shouldBe HardcoverCandidatesResult.Unauthorized
            }
        }
    })
