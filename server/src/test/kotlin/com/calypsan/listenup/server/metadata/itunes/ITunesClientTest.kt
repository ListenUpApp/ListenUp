@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.metadata.itunes

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
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class ITunesClientTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun makeClient(engine: MockEngine): ITunesClient {
            val httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(json) }
            }
            return ITunesClient(httpClient, json)
        }

        // ─── findCover — success path ─────────────────────────────────────────

        test("findCover returns ITunesCoverHit for a matched result") {
            runTest {
                val engine = MockEngine { _ ->
                    respond(
                        content = SEARCH_WITH_RESULTS,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                val client = makeClient(engine)
                val result = client.findCover("Project Hail Mary", "Andy Weir")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                val hit = (result as AppResult.Success<ITunesCoverHit?>).data
                hit.shouldNotBeNull()
                hit.coverUrl shouldBe "https://is1-ssl.mzstatic.com/image/thumb/Music/foo/source/100x100bb.jpg"
                hit.maxSizeUrl shouldBe "https://is1-ssl.mzstatic.com/image/thumb/Music/foo/source/7000x7000bb.jpg"
            }
        }

        test("findCover returns Success(null) when resultCount is 0") {
            runTest {
                val engine = MockEngine { _ ->
                    respond(
                        content = """{"resultCount":0,"results":[]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                val client = makeClient(engine)
                val result = client.findCover("Unknown Book", "Nobody")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                (result as AppResult.Success<ITunesCoverHit?>).data.shouldBeNull()
            }
        }

        test("findCover returns first result when no title+author match but results exist") {
            runTest {
                val engine = MockEngine { _ ->
                    respond(
                        content = SEARCH_NO_MATCH,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                val client = makeClient(engine)
                // query that won't fuzzy-match "Some Other Title" but there is a result
                val result = client.findCover("Completely Different", "Another Author")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                // falls back to first result
                (result as AppResult.Success<ITunesCoverHit?>).data.shouldNotBeNull()
            }
        }

        // ─── URL transformation ───────────────────────────────────────────────

        test("maxSizeUrl replaces size fragment with 7000x7000bb.jpg") {
            runTest {
                val engine = MockEngine { _ ->
                    respond(
                        content = SEARCH_WITH_RESULTS,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                val client = makeClient(engine)
                val result = client.findCover("Project Hail Mary", "Andy Weir")

                val hit = (result as AppResult.Success<ITunesCoverHit?>).data!!
                // The regex must replace ANY size fragment, not just 100x100
                hit.maxSizeUrl shouldBe "https://is1-ssl.mzstatic.com/image/thumb/Music/foo/source/7000x7000bb.jpg"
            }
        }

        test("maxSizeUrl transformation handles 60x60bb.jpg fallback URL") {
            runTest {
                val engine = MockEngine { _ ->
                    respond(
                        content = SEARCH_WITH_60PX_ONLY,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                val client = makeClient(engine)
                val result = client.findCover("A Book", "Author")

                val hit = (result as AppResult.Success<ITunesCoverHit?>).data!!
                hit.maxSizeUrl shouldBe "https://example.com/art/7000x7000bb.jpg"
            }
        }

        // ─── error mapping ────────────────────────────────────────────────────

        test("findCover returns ExternalRateLimited on HTTP 429") {
            runTest {
                val engine = MockEngine { _ ->
                    respond(content = "", status = HttpStatusCode.TooManyRequests)
                }
                val client = makeClient(engine)
                val result = client.findCover("Any", "Author")

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.ExternalRateLimited>()
            }
        }

        test("findCover returns ExternalUnavailable on HTTP 500") {
            runTest {
                val engine = MockEngine { _ ->
                    respond(content = "", status = HttpStatusCode.InternalServerError)
                }
                val client = makeClient(engine)
                val result = client.findCover("Any", "Author")

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.ExternalUnavailable>()
            }
        }

        test("findCover returns ExternalUnavailable on HTTP 503") {
            runTest {
                val engine = MockEngine { _ ->
                    respond(content = "", status = HttpStatusCode.ServiceUnavailable)
                }
                val client = makeClient(engine)
                val result = client.findCover("Any", "Author")

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.ExternalUnavailable>()
            }
        }

        test("findCover returns Malformed on invalid JSON") {
            runTest {
                val engine = MockEngine { _ ->
                    respond(
                        content = """{"results": "not an array"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                val client = makeClient(engine)
                val result = client.findCover("Any", "Author")

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.Malformed>()
            }
        }

        // ─── CancellationException propagation ────────────────────────────────

        test("CancellationException from the engine propagates rather than being swallowed") {
            runTest {
                // Simulate an engine that throws CancellationException internally
                // (e.g. the job was cancelled while the request was in-flight).
                val engine = MockEngine { _ ->
                    throw CancellationException("simulated cancellation")
                }
                val client = makeClient(engine)
                var threw = false
                try {
                    client.findCover("Any", "Author")
                } catch (e: CancellationException) {
                    threw = true
                }
                threw shouldBe true
            }
        }
    })

// ─── Fixture JSON ─────────────────────────────────────────────────────────────

/** A matched result: collectionName and artistName match the query terms. */
private val SEARCH_WITH_RESULTS =
    """
    {
      "resultCount": 1,
      "results": [
        {
          "wrapperType": "audiobook",
          "collectionType": "Audiobook",
          "collectionId": 123456,
          "collectionName": "Project Hail Mary",
          "artistName": "Andy Weir",
          "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music/foo/source/100x100bb.jpg",
          "artworkUrl60": "https://is1-ssl.mzstatic.com/image/thumb/Music/foo/source/60x60bb.jpg"
        }
      ]
    }
    """.trimIndent()

/** A result whose name doesn't match the query — tests best-effort fallback. */
private val SEARCH_NO_MATCH =
    """
    {
      "resultCount": 1,
      "results": [
        {
          "wrapperType": "audiobook",
          "collectionType": "Audiobook",
          "collectionId": 789,
          "collectionName": "Some Other Title",
          "artistName": "Some Other Author",
          "artworkUrl100": "https://example.com/art/100x100bb.jpg"
        }
      ]
    }
    """.trimIndent()

/** Only a 60px artwork URL present — 100px field is absent. */
private val SEARCH_WITH_60PX_ONLY =
    """
    {
      "resultCount": 1,
      "results": [
        {
          "wrapperType": "audiobook",
          "collectionType": "Audiobook",
          "collectionId": 999,
          "collectionName": "A Book",
          "artistName": "Author",
          "artworkUrl60": "https://example.com/art/60x60bb.jpg"
        }
      ]
    }
    """.trimIndent()
