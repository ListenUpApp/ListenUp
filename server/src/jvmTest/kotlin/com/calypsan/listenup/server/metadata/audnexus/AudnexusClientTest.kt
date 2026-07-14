@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.metadata.audnexus

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.time.Duration

class AudnexusClientTest :
    FunSpec({
        val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        // Captures the last request URL so tests can assert the base host, path, and region param.
        fun clientCapturing(
            status: HttpStatusCode,
            body: String,
            captured: MutableList<Url>,
            baseUrl: String = AudnexusClient.DEFAULT_BASE_URL,
        ): AudnexusClient {
            val engine =
                MockEngine { request ->
                    captured += request.url
                    respond(
                        content = body,
                        status = status,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
            return AudnexusClient(
                httpClient = HttpClient(engine),
                json = json,
                rateLimiter = AudnexusRateLimiter(minInterval = Duration.ZERO),
                baseUrl = baseUrl,
            )
        }

        fun client(
            status: HttpStatusCode,
            body: String,
        ): AudnexusClient = clientCapturing(status, body, mutableListOf())

        test("getBook decodes the book and passes asin + region to the public host") {
            runTest {
                val captured = mutableListOf<Url>()
                val result = clientCapturing(HttpStatusCode.OK, BOOK_JSON, captured).getBook("B01", "uk")

                val book = result.shouldBeInstanceOf<AppResult.Success<AudnexusBook?>>().data
                book.shouldNotBeNull()
                book.title shouldBe "The Way of Kings"
                book.seriesPrimary?.name shouldBe "The Stormlight Archive"
                book.authors.single().asin shouldBe "A1"
                book.narrators.map { it.name } shouldBe listOf("Kate Reading")
                book.genres.map { it.name to it.type } shouldBe listOf("Fantasy" to "genre", "Slow Burn" to "tag")

                val url = captured.single()
                url.host shouldBe "api.audnex.us"
                url.encodedPath shouldBe "/books/B01"
                url.parameters["region"] shouldBe "uk"
            }
        }

        test("getBook maps a 404 to Success(null) — a catalog miss, not a failure") {
            runTest {
                client(HttpStatusCode.NotFound, "")
                    .getBook("MISSING", "us")
                    .shouldBeInstanceOf<AppResult.Success<AudnexusBook?>>()
                    .data
                    .shouldBeNull()
            }
        }

        test("getChapters decodes chapters with isAccurate and brand offsets") {
            runTest {
                val chapters =
                    client(HttpStatusCode.OK, CHAPTERS_JSON)
                        .getChapters("B01", "us")
                        .shouldBeInstanceOf<AppResult.Success<AudnexusChapters?>>()
                        .data
                chapters.shouldNotBeNull()
                chapters.isAccurate shouldBe true
                chapters.brandIntroDurationMs shouldBe 2043
                chapters.chapters.first().title shouldBe "Prologue"
            }
        }

        test("searchAuthors decodes the hit list and appends name + region") {
            runTest {
                val captured = mutableListOf<Url>()
                val hits =
                    clientCapturing(HttpStatusCode.OK, AUTHORS_JSON, captured)
                        .searchAuthors("rothfuss", "de")
                        .shouldBeInstanceOf<AppResult.Success<List<AudnexusAuthor>>>()
                        .data
                hits.map { it.asin } shouldBe listOf("A1", "A2")

                val url = captured.single()
                url.encodedPath shouldBe "/authors"
                url.parameters["name"] shouldBe "rothfuss"
                url.parameters["region"] shouldBe "de"
            }
        }

        test("searchAuthors maps a 404 to an empty list") {
            runTest {
                client(HttpStatusCode.NotFound, "")
                    .searchAuthors("nobody", "us")
                    .shouldBeInstanceOf<AppResult.Success<List<AudnexusAuthor>>>()
                    .data shouldBe emptyList()
            }
        }

        test("getAuthor decodes the profile") {
            runTest {
                val profile =
                    client(HttpStatusCode.OK, AUTHOR_JSON)
                        .getAuthor("A1", "us")
                        .shouldBeInstanceOf<AppResult.Success<AudnexusAuthorProfile?>>()
                        .data
                profile.shouldNotBeNull()
                profile.name shouldBe "Brandon Sanderson"
                profile.description shouldBe "American author."
                profile.image shouldBe "https://a/photo.jpg"
            }
        }

        test("getAuthor maps a 404 to Success(null)") {
            runTest {
                client(HttpStatusCode.NotFound, "")
                    .getAuthor("MISSING", "us")
                    .shouldBeInstanceOf<AppResult.Success<AudnexusAuthorProfile?>>()
                    .data
                    .shouldBeNull()
            }
        }

        test("a 429 maps to ExternalRateLimited") {
            runTest {
                client(HttpStatusCode.TooManyRequests, "")
                    .getBook("B01", "us")
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<MetadataError.ExternalRateLimited>()
            }
        }

        test("a 500 maps to ExternalUnavailable") {
            runTest {
                client(HttpStatusCode.InternalServerError, "")
                    .getBook("B01", "us")
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<MetadataError.ExternalUnavailable>()
            }
        }

        test("unparseable JSON maps to Malformed") {
            runTest {
                client(HttpStatusCode.OK, """{"authors": "not-an-object"}""")
                    .getBook("B01", "us")
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<MetadataError.Malformed>()
            }
        }

        test("CancellationException from the engine propagates rather than being swallowed") {
            runTest {
                val engine = MockEngine { throw CancellationException("cancelled") }
                val client =
                    AudnexusClient(HttpClient(engine), json, AudnexusRateLimiter(minInterval = Duration.ZERO))
                var threw = false
                try {
                    client.getBook("B01", "us")
                } catch (_: CancellationException) {
                    threw = true
                }
                threw shouldBe true
            }
        }

        test("baseUrl override (LISTENUP_AUDNEXUS_URL) points requests at the operator's mirror") {
            runTest {
                val captured = mutableListOf<Url>()
                clientCapturing(HttpStatusCode.OK, BOOK_JSON, captured, baseUrl = "https://audnex.mirror.local")
                    .getBook("B01", "us")
                captured.single().host shouldBe "audnex.mirror.local"
            }
        }
    })

// ─── Fixture JSON ─────────────────────────────────────────────────────────────

private val BOOK_JSON =
    """
    {
      "asin": "B01",
      "title": "The Way of Kings",
      "subtitle": "The Stormlight Archive, Book 1",
      "description": "Roshar is a world of stone and storms.",
      "publisherName": "Macmillan Audio",
      "releaseDate": "2010-08-31",
      "language": "english",
      "image": "https://a/cover.jpg",
      "seriesPrimary": { "asin": "S1", "name": "The Stormlight Archive", "position": "1" },
      "genres": [
        { "asin": "G1", "name": "Fantasy", "type": "genre" },
        { "asin": "T1", "name": "Slow Burn", "type": "tag" }
      ],
      "authors": [ { "asin": "A1", "name": "Brandon Sanderson" } ],
      "narrators": [ { "name": "Kate Reading" } ]
    }
    """.trimIndent()

private val CHAPTERS_JSON =
    """
    {
      "asin": "B01",
      "brandIntroDurationMs": 2043,
      "brandOutroDurationMs": 5061,
      "isAccurate": true,
      "runtimeLengthMs": 9000000,
      "chapters": [
        { "title": "Prologue", "startOffsetMs": 0, "lengthMs": 120000 },
        { "title": "Chapter 1", "startOffsetMs": 120000, "lengthMs": 300000 }
      ]
    }
    """.trimIndent()

private val AUTHORS_JSON =
    """
    [
      { "asin": "A1", "name": "Patrick Rothfuss" },
      { "asin": "A2", "name": "Pat Rothfuss" }
    ]
    """.trimIndent()

private val AUTHOR_JSON =
    """
    {
      "asin": "A1",
      "name": "Brandon Sanderson",
      "description": "American author.",
      "image": "https://a/photo.jpg"
    }
    """.trimIndent()
