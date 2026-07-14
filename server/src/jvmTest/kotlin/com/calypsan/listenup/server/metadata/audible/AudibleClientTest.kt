@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.metadata.audible

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondRedirect
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
        val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        fun makeClient(engine: MockEngine): AudibleClient {
            val httpClient =
                HttpClient(engine) {
                    install(ContentNegotiation) { json(json) }
                }
            // Clock always at epoch — rate limiter always grants immediately in tests.
            val rateLimiter =
                AudibleRateLimiter(
                    perRegionInterval = 0.seconds,
                    clock =
                        object : kotlin.time.Clock {
                            override fun now() = Instant.fromEpochMilliseconds(0)
                        },
                )
            return AudibleClient(httpClient, rateLimiter, json)
        }

        // ─── search ───────────────────────────────────────────────────────────

        test("search returns Success with parsed results on 200") {
            runTest {
                val engine =
                    MockEngine { _ ->
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
                val engine =
                    MockEngine { _ ->
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
                val engine =
                    MockEngine { _ ->
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
                val engine =
                    MockEngine { _ ->
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
                val engine =
                    MockEngine { _ ->
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
                val engine =
                    MockEngine { _ ->
                        respond(
                            content = SEARCH_200,
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    }
                val httpClient =
                    HttpClient(engine) {
                        install(ContentNegotiation) { json(json) }
                    }
                val countingLimiter =
                    object : AudibleRateLimiter(
                        perRegionInterval = 0.seconds,
                        clock =
                            object : kotlin.time.Clock {
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
                val engine =
                    MockEngine { _ ->
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

        test("getBook preserves the category ladder hierarchy in genreLadders") {
            runTest {
                val engine =
                    MockEngine { _ ->
                        respond(
                            content = BOOK_WITH_NESTED_LADDER_200,
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    }
                val client = makeClient(engine)
                val book = (client.getBook(AudibleRegion.US, "B0LADDER") as AppResult.Success<AudibleBook?>).data

                // Hierarchy preserved root → leaf …
                book?.genreLadders shouldBe listOf(listOf("Fiction", "Fantasy", "LitRPG"))
                // … while the flat list is the deduped rungs (back-compat).
                book?.genres shouldBe listOf("Fiction", "Fantasy", "LitRPG")
            }
        }

        // ─── getChapters ──────────────────────────────────────────────────────

        test("getChapters returns Success with parsed chapters on 200") {
            runTest {
                val engine =
                    MockEngine { _ ->
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

        // ─── getContributor ───────────────────────────────────────────────────

        test("getContributor returns Success with parsed profile when og:image is present") {
            runTest {
                val engine =
                    MockEngine { _ ->
                        respond(
                            content = CONTRIBUTOR_PAGE_WITH_OG_IMAGE,
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "text/html"),
                        )
                    }
                val client = makeClient(engine)
                val result = client.getContributor(AudibleRegion.US, "B000APZOQA")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                val profile = (result as AppResult.Success<AudibleContributorProfile?>).data
                profile.shouldNotBeNull()
                profile.asin shouldBe "B000APZOQA"
                profile.name shouldBe "Frank Herbert"
                profile.biography shouldBe "Author of Dune"
                profile.imageUrl shouldBe "https://images.example.com/frank_herbert.jpg"
            }
        }

        test("getContributor extracts the photo from author-image-outline when there is no og:image (#615)") {
            runTest {
                val engine =
                    MockEngine { _ ->
                        respond(
                            content = CONTRIBUTOR_PAGE_REAL_AUTHOR_IMAGE,
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "text/html"),
                        )
                    }
                val client = makeClient(engine)
                val result = client.getContributor(AudibleRegion.US, "B001IGFHW6")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                val profile = (result as AppResult.Success<AudibleContributorProfile?>).data
                profile.shouldNotBeNull()
                profile.name shouldBe "Brandon Sanderson"
                // The 120px thumbnail src is upsized to a 600px rendition.
                profile.imageUrl shouldBe
                    "https://images-na.ssl-images-amazon.com/images/S/amzn-author-media-prod/" +
                    "o1ehbft4gejvtoskr22jt89eit._SX600_.jpg"
            }
        }

        test("getContributor returns null (Success) on 404 — Audible serves generic page for unknown ASINs") {
            runTest {
                val engine =
                    MockEngine { _ ->
                        respond(content = "", status = HttpStatusCode.NotFound)
                    }
                val client = makeClient(engine)
                val result = client.getContributor(AudibleRegion.US, "UNKNOWN")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                (result as AppResult.Success<*>).data shouldBe null
            }
        }

        test("getContributor returns ExternalRateLimited on 429") {
            runTest {
                val engine =
                    MockEngine { _ ->
                        respond(content = "", status = HttpStatusCode.TooManyRequests)
                    }
                val client = makeClient(engine)
                val result = client.getContributor(AudibleRegion.US, "B000APZOQA")

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.ExternalRateLimited>()
            }
        }

        test("getContributor sends the storefront locale Cookie header on the web-host request") {
            runTest {
                var sentCookie: String? = null
                val engine =
                    MockEngine { request ->
                        sentCookie = request.headers["Cookie"]
                        respond(
                            content = CONTRIBUTOR_PAGE_WITH_OG_IMAGE,
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "text/html"),
                        )
                    }
                val client = makeClient(engine)
                client.getContributor(AudibleRegion.UK, "B000APZOQA")

                sentCookie shouldBe "lc-acbuk=en_GB; i18n-prefs=GBP"
            }
        }

        test("searchContributors sends the storefront locale Cookie header — fixes non-US lookups (#551)") {
            runTest {
                var sentCookie: String? = null
                val engine =
                    MockEngine { request ->
                        sentCookie = request.headers["Cookie"]
                        respond(
                            content = "<html><body></body></html>",
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "text/html"),
                        )
                    }
                val client = makeClient(engine)
                client.searchContributors(AudibleRegion.DE, "Frank Herbert")

                sentCookie shouldBe "lc-acbde=de_DE; i18n-prefs=EUR"
            }
        }

        test("getContributor returns null when page has no bc-heading h1 — generic/unknown page") {
            runTest {
                val engine =
                    MockEngine { _ ->
                        respond(
                            content = "<html><body><p>Not found</p></body></html>",
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "text/html"),
                        )
                    }
                val client = makeClient(engine)
                val result = client.getContributor(AudibleRegion.US, "B000APZOQA")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                (result as AppResult.Success<*>).data shouldBe null
            }
        }

        // ─── getProductTags ─────────────────────────────────────────────────────

        test("getProductTags returns Success with parsed typed tags on 200") {
            runTest {
                val engine =
                    MockEngine { _ ->
                        respond(
                            content = PRODUCT_TAGS_PAGE,
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "text/html"),
                        )
                    }
                val client = makeClient(engine)
                val result = client.getProductTags(AudibleRegion.US, "B0CBP6NQVR")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                val tags = (result as AppResult.Success<List<ProductTag>>).data
                tags shouldBe
                    listOf(
                        ProductTag("mood", "Witty"),
                        ProductTag("theme", "LitRPG"),
                    )
            }
        }

        test("getProductTags returns empty list (Success) on 404") {
            runTest {
                val engine =
                    MockEngine { _ ->
                        respond(content = "", status = HttpStatusCode.NotFound)
                    }
                val client = makeClient(engine)
                val result = client.getProductTags(AudibleRegion.US, "UNKNOWN")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                (result as AppResult.Success<List<ProductTag>>).data shouldBe emptyList()
            }
        }

        test("getProductTags sends the storefront locale Cookie on the /pd web-host request") {
            runTest {
                var sentCookie: String? = null
                val engine =
                    MockEngine { request ->
                        sentCookie = request.headers["Cookie"]
                        respond(
                            content = PRODUCT_TAGS_PAGE,
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "text/html"),
                        )
                    }
                val client = makeClient(engine)
                client.getProductTags(AudibleRegion.UK, "B0CBP6NQVR")

                sentCookie shouldBe "lc-acbuk=en_GB; i18n-prefs=GBP"
            }
        }

        test("getProductTags surfaces ExternalUnavailable when Audible IP-redirects the storefront away") {
            runTest {
                // From an out-of-region IP, Audible redirects www.audible.ca off to the .com homepage
                // (Adbl_ip_rdr_from_CA / ipRedirectFrom=CA) — the /pd page (and its topic tags) never
                // loads. The scrape must surface this, not silently parse the wrong page and return empty.
                val engine =
                    MockEngine { request ->
                        if (request.url.host == "www.audible.ca") {
                            respondRedirect("https://www.audible.com/?ref=Adbl_ip_rdr_from_CA&ipRedirectFrom=CA")
                        } else {
                            respond(
                                content = "<html><body>store homepage — no topic tags</body></html>",
                                status = HttpStatusCode.OK,
                                headers = headersOf("Content-Type", "text/html"),
                            )
                        }
                    }
                val client = makeClient(engine)
                val result = client.getProductTags(AudibleRegion.CA, "B08G9PRS1K")

                result.shouldBeInstanceOf<AppResult.Failure>()
                (result as AppResult.Failure).error.shouldBeInstanceOf<MetadataError.ExternalUnavailable>()
            }
        }
    })

// ─── Fixture JSON ─────────────────────────────────────────────────────────────

private val SEARCH_200 =
    """
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

private val BOOK_200 =
    """
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

private val BOOK_WITH_NESTED_LADDER_200 =
    """
{
  "product": {
    "asin": "B0LADDER",
    "title": "Dungeon Crawler",
    "subtitle": "",
    "publisher_name": "Test Audio",
    "release_date": "2020-01-01",
    "runtime_length_min": 600,
    "merchandising_summary": "<p>A ladder fixture</p>",
    "product_images": {"500": "https://example.com/cover500.jpg"},
    "authors": [{"asin": "A1", "name": "Test Author", "role": "author"}],
    "narrators": [],
    "series": [],
    "category_ladders": [
      {"ladder": [
        {"id": "1", "name": "Fiction"},
        {"id": "2", "name": "Fantasy"},
        {"id": "3", "name": "LitRPG"}
      ]}
    ],
    "language": "english",
    "rating": null
  }
}
    """.trimIndent()

private val CHAPTERS_200 =
    """
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

// ─── Contributor page HTML fixture ────────────────────────────────────────────

/**
 * Minimal Audible author page HTML with:
 *  - `<h1 class="bc-heading">Frank Herbert</h1>` → name
 *  - `class="bc-expander-content"` → biography text
 *  - `<meta property="og:image" content="...">` → image URL (not a placeholder)
 */
private val CONTRIBUTOR_PAGE_WITH_OG_IMAGE =
    """
<!DOCTYPE html>
<html>
<head>
<meta property="og:image" content="https://images.example.com/frank_herbert.jpg">
</head>
<body>
<h1 class="bc-heading">Frank Herbert</h1>
<div class="bc-expander-content">Author of Dune</div>
</body>
</html>
    """.trimIndent()

/**
 * Author page HTML mirroring the REAL Audible structure (verified against a live author page):
 * there is **no** `og:image` meta tag — the photo lives only in the
 * `author-image-outline` `<img>`, whose `src` carries Amazon size params (commas) and is followed
 * by an `onerror` attribute. Guards that the `author-image-outline` fallback (not just the
 * synthetic og:image fixture) extracts a real photo URL.
 */
private val CONTRIBUTOR_PAGE_REAL_AUTHOR_IMAGE =
    """
<!DOCTYPE html>
<html>
<head><title>Brandon Sanderson | Audible.ca</title></head>
<body>
<h1 class="bc-heading">Brandon Sanderson</h1>
<div class="bc-expander-content">Author of The Stormlight Archive</div>
<img alt="" class="image-mask app-image-position author-image-outline js-only-element" src="https://images-na.ssl-images-amazon.com/images/S/amzn-author-media-prod/o1ehbft4gejvtoskr22jt89eit.__01_SX120_CR0,0,120,120__.jpg" onerror="this.src='https://example.com/fallback.jpg'">
</body>
</html>
    """.trimIndent()

// ─── Product page topic-tag fixture ───────────────────────────────────────────

/** Minimal `/pd` page slice with one `mood` and one `theme` topic-tag anchor (with tracking params). */
private val PRODUCT_TAGS_PAGE =
    """
<!DOCTYPE html>
<html>
<body>
<div class="product-topictag-impression">
  <a href="/tag/mood/Witty-Audiobooks/adbl_rec_tag_001?ref=a_pd">Witty</a>
  <a href="/tag/theme/LitRPG-Audiobooks/adbl_rec_tag_002?ref=a_pd">LitRPG</a>
</div>
</body>
</html>
    """.trimIndent()
