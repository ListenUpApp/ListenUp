@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.metadata.custom

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataDomain
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.EnrichmentCoordinator
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.CharacterMeta
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.GenreKind
import com.calypsan.listenup.server.metadata.spi.GenreMeta
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.time.Duration

private val US = MetadataLocale("us")
private val ALL = CustomProviderSpec.SUPPORTED_CAPABILITIES

private val JSON =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

private fun identity() = BookIdentity(asin = "B01", title = "The Way of Kings", primaryAuthor = "Brandon Sanderson")

/**
 * Builds a [CustomHttpProvider] over a MockEngine that records every request and responds
 * per request path via [respondFor]. [capabilities] gates which domains the provider advertises.
 */
private fun provider(
    capabilities: Set<MetadataDomain> = ALL,
    requests: MutableList<Url> = mutableListOf(),
    respondFor: (path: String) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to "[]" },
): CustomHttpProvider {
    val engine =
        MockEngine { request ->
            requests += request.url
            val (status, body) = respondFor(request.url.encodedPath)
            respond(body, status, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
    val client =
        CustomMetadataClient(
            httpClient = HttpClient(engine),
            json = JSON,
            baseUrl = "https://custom.test",
            rateLimiter = CustomRateLimiter(minInterval = Duration.ZERO),
        )
    val spec = CustomProviderSpec(MetadataProviderId.custom("mysource"), "mysource", "https://custom.test", capabilities)
    return CustomHttpProvider(spec, client)
}

class CustomHttpProviderTest :
    FunSpec({

        test("getBookCore fetches /book and maps the JSON to BookCoreMeta with credits folded in") {
            runTest {
                val captured = mutableListOf<Url>()
                val core =
                    provider(requests = captured) { HttpStatusCode.OK to BOOK_JSON }
                        .getBookCore(identity(), US, refresh = false)
                        .shouldBeInstanceOf<AppResult.Success<BookCoreMeta?>>()
                        .data
                core.shouldNotBeNull()
                core.title shouldBe "The Way of Kings"
                core.description shouldBe "Stone and storms."
                core.runtimeMinutes shouldBe 2734
                core.authors.map { it.name to it.role } shouldContainExactly listOf("Brandon Sanderson" to ContributorRole.AUTHOR)
                core.narrators.map { it.name to it.role } shouldContainExactly listOf("Kate Reading" to ContributorRole.NARRATOR)

                // The lookup key params reach the endpoint.
                val url = captured.single()
                url.encodedPath shouldBe "/book"
                url.parameters["asin"] shouldBe "B01"
                url.parameters["title"] shouldBe "The Way of Kings"
                url.parameters["author"] shouldBe "Brandon Sanderson"
                url.parameters["region"] shouldBe "us"
            }
        }

        test("getCharacters fetches /characters and maps the JSON to CharacterMeta") {
            runTest {
                val characters =
                    provider { HttpStatusCode.OK to CHARACTERS_JSON }
                        .getCharacters(identity(), US)
                        .shouldBeInstanceOf<AppResult.Success<List<CharacterMeta>?>>()
                        .data
                characters.shouldNotBeNull()
                characters.map { it.name } shouldContainExactly listOf("Kaladin", "Shallan")
                characters.first().description shouldBe "A bridgeman."
                characters[1].description.shouldBeNull()
            }
        }

        test("getGenres maps kind=tag to a free-form tag and everything else to a formal genre") {
            runTest {
                val genres =
                    provider { HttpStatusCode.OK to GENRES_JSON }
                        .getGenres(identity(), US)
                        .shouldBeInstanceOf<AppResult.Success<List<GenreMeta>?>>()
                        .data
                genres.shouldNotBeNull()
                genres.map { it.name to it.kind } shouldContainExactly
                    listOf("Fantasy" to GenreKind.GENRE, "Slow Burn" to GenreKind.TAG)
            }
        }

        test("an undeclared capability is an immediate honest miss — no HTTP request is issued") {
            runTest {
                val captured = mutableListOf<Url>()
                val result =
                    provider(capabilities = setOf(MetadataDomain.CHARACTERS), requests = captured)
                        .getBookCore(identity(), US, refresh = false)

                result.shouldBeInstanceOf<AppResult.Success<*>>().data.shouldBeNull()
                captured.shouldBeEmpty()
            }
        }

        test("a declared capability whose endpoint 404s is an honest catalog miss, not a failure") {
            runTest {
                provider { HttpStatusCode.NotFound to "" }
                    .getCharacters(identity(), US)
                    .shouldBeInstanceOf<AppResult.Success<*>>()
                    .data
                    .shouldBeNull()
            }
        }

        test("a 429 maps to ExternalRateLimited and a 500 to ExternalUnavailable") {
            runTest {
                provider { HttpStatusCode.TooManyRequests to "" }
                    .getCharacters(identity(), US)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<MetadataError.ExternalRateLimited>()

                provider { HttpStatusCode.InternalServerError to "" }
                    .getCharacters(identity(), US)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<MetadataError.ExternalUnavailable>()
            }
        }

        test("unparseable JSON maps to Malformed") {
            runTest {
                provider { HttpStatusCode.OK to """{"not":"an-array"}""" }
                    .getCharacters(identity(), US)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<MetadataError.Malformed>()
            }
        }

        test("a configured custom characters source flows through the coordinator when CHARACTERS is routed to it") {
            runTest {
                val custom = provider(capabilities = setOf(MetadataDomain.CHARACTERS)) { HttpStatusCode.OK to CHARACTERS_JSON }
                val routes = EnrichmentRoutes.parse(order = null, routes = "characters=custom:mysource")
                val coordinator = EnrichmentCoordinator(MetadataProviderRegistry(listOf(custom)), routes)

                coordinator.composeCharacters(identity(), US).map { it.name } shouldContainExactly listOf("Kaladin", "Shallan")
            }
        }
    })

// ─── Fixture JSON (the documented custom-provider contract) ────────────────────

private val BOOK_JSON =
    """
    {
      "title": "The Way of Kings",
      "subtitle": "Book 1",
      "description": "Stone and storms.",
      "publisher": "Macmillan Audio",
      "releaseDate": "2010-08-31",
      "language": "english",
      "runtimeMinutes": 2734,
      "authors": [ { "key": "A1", "name": "Brandon Sanderson" } ],
      "narrators": [ { "name": "Kate Reading" } ]
    }
    """.trimIndent()

private val CHARACTERS_JSON =
    """
    [
      { "name": "Kaladin", "description": "A bridgeman." },
      { "name": "Shallan" }
    ]
    """.trimIndent()

private val GENRES_JSON =
    """
    [
      { "name": "Fantasy", "kind": "genre" },
      { "name": "Slow Burn", "kind": "tag" }
    ]
    """.trimIndent()
