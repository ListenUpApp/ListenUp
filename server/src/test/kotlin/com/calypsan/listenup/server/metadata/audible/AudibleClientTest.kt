@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.metadata.audible

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AudibleClientTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun makeClient(engine: MockEngine): AudibleClient {
            val httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(json) }
            }
            // Clock always at epoch — rate limiter always grants immediately in tests.
            val rateLimiter = AudibleRateLimiter(
                perRegionInterval = 0.seconds,
                clock = object : kotlin.time.Clock {
                    override fun now() = Instant.fromEpochMilliseconds(0)
                },
            )
            return AudibleClient(httpClient, rateLimiter, json)
        }

        // ─── search ───────────────────────────────────────────────────────────

        test("search returns Success with parsed results on 200") {
            runTest {
                val engine = MockEngine { _ ->
                    respond(
                        content = SEARCH_200,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                val client = makeClient(engine)
                val result = client.search(AudibleRegion.US, SearchParams(keywords = "dune"))

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                val results = (result as AppResult.Success<List<AudibleSearchResult>>).data
                results.size shouldBe 1
                results[0].asin shouldBe "B002V5DFJ4"
                results[0].title shouldBe "Dune"
            }
        }

        test("search returns ExternalRateLimited on 429") {
            runTest {
                val engine = MockEngine { _ ->
                    respond(content = "", status = HttpStatusCode.TooManyRequests)
                }
                val client = makeClient(engine)
                val result = client.search(AudibleRegion.US, SearchParams(keywords = "dune"))

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.ExternalRateLimited>()
            }
        }

        test("search returns ExternalUnavailable on 500") {
            runTest {
                val engine = MockEngine { _ ->
                    respond(content = "", status = HttpStatusCode.InternalServerError)
                }
                val client = makeClient(engine)
                val result = client.search(AudibleRegion.US, SearchParams(keywords = "dune"))

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.ExternalUnavailable>()
            }
        }

        test("search returns NotFound on 404") {
            runTest {
                val engine = MockEngine { _ ->
                    respond(content = "", status = HttpStatusCode.NotFound)
                }
                val client = makeClient(engine)
                val result = client.search(AudibleRegion.US, SearchParams(keywords = "dune"))

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.NotFound>()
            }
        }

        test("search returns Malformed on invalid JSON") {
            runTest {
                val engine = MockEngine { _ ->
                    respond(
                        content = """{"products": "not an array"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                val client = makeClient(engine)
                val result = client.search(AudibleRegion.US, SearchParams(keywords = "dune"))

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.Malformed>()
            }
        }

        test("search calls rate limiter before making the HTTP request") {
            runTest {
                var rateLimiterCallCount = 0
                val engine = MockEngine { _ ->
                    respond(
                        content = SEARCH_200,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                val httpClient = HttpClient(engine) {
                    install(ContentNegotiation) { json(json) }
                }
                val countingLimiter = object : AudibleRateLimiter(
                    perRegionInterval = 0.seconds,
                    clock = object : kotlin.time.Clock {
                        override fun now() = Instant.fromEpochMilliseconds(0)
                    },
                ) {
                    override suspend fun await(region: AudibleRegion) {
                        rateLimiterCallCount++
                        super.await(region)
                    }
                }
                val client = AudibleClient(httpClient, countingLimiter, json)
                client.search(AudibleRegion.US, SearchParams(keywords = "dune"))

                rateLimiterCallCount shouldBe 1
            }
        }

        // ─── getBook ──────────────────────────────────────────────────────────

        test("getBook returns Success with parsed book on 200") {
            runTest {
                val engine = MockEngine { _ ->
                    respond(
                        content = BOOK_200,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                val client = makeClient(engine)
                val result = client.getBook(AudibleRegion.US, "B002V5DFJ4")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                val book = (result as AppResult.Success<AudibleBook?>).data
                book?.asin shouldBe "B002V5DFJ4"
                book?.title shouldBe "Dune"
            }
        }

        // ─── getChapters ──────────────────────────────────────────────────────

        test("getChapters returns Success with parsed chapters on 200") {
            runTest {
                val engine = MockEngine { _ ->
                    respond(
                        content = CHAPTERS_200,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                val client = makeClient(engine)
                val result = client.getChapters(AudibleRegion.US, "B002V5DFJ4")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                val chapters = (result as AppResult.Success<List<AudibleChapter>>).data
                chapters.size shouldBe 2
                chapters[0].title shouldBe "Prologue"
                chapters[0].startMs shouldBe 0L
                chapters[1].title shouldBe "Chapter 1"
                chapters[1].startMs shouldBe 45_000L
            }
        }
    })

// ─── Fixture JSON ─────────────────────────────────────────────────────────────

private val SEARCH_200 = """
{
  "products": [
    {
      "asin": "B002V5DFJ4",
      "title": "Dune",
      "subtitle": "",
      "publisher_name": "Macmillan Audio",
      "release_date": "2007-09-11",
      "runtime_length_min": 1000,
      "merchandising_summary": "Science fiction masterpiece",
      "product_images": {"500": "https://example.com/cover500.jpg", "1024": "https://example.com/cover1024.jpg"},
      "authors": [{"asin": "B000APZOQA", "name": "Frank Herbert", "role": "author"}],
      "narrators": [{"asin": "", "name": "Scott Brick", "role": "narrator"}],
      "series": [],
      "category_ladders": [],
      "language": "english",
      "rating": {
        "overall_distribution": {
          "display_average_rating": 4.8,
          "num_reviews": 50000
        }
      }
    }
  ]
}
""".trimIndent()

private val BOOK_200 = """
{
  "product": {
    "asin": "B002V5DFJ4",
    "title": "Dune",
    "subtitle": "",
    "publisher_name": "Macmillan Audio",
    "release_date": "2007-09-11",
    "runtime_length_min": 1000,
    "merchandising_summary": "<p>Science fiction masterpiece</p>",
    "product_images": {"500": "https://example.com/cover500.jpg"},
    "authors": [{"asin": "B000APZOQA", "name": "Frank Herbert", "role": "author"}],
    "narrators": [{"asin": "", "name": "Scott Brick", "role": "narrator"}],
    "series": [],
    "category_ladders": [{"ladder": [{"id": "18685580011", "name": "Science Fiction"}]}],
    "language": "english",
    "rating": null
  }
}
""".trimIndent()

private val CHAPTERS_200 = """
{
  "content_metadata": {
    "chapter_info": {
      "chapters": [
        {"title": "Prologue", "start_offset_ms": 0, "start_offset_sec": 0, "length_ms": 45000},
        {"title": "Chapter 1", "start_offset_ms": 45000, "start_offset_sec": 45, "length_ms": 900000}
      ]
    }
  }
}
""".trimIndent()
