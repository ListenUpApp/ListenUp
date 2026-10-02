package com.calypsan.listenup.server.hardcover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private val WEIR = FakeHardcoverCatalog.Author(7L, "Andy Weir", bio = "Writes.", imageUrl = "https://hc.test/weir.jpg")
private val WEIR_PROFILE = HardcoverAuthorProfile(7L, "Andy Weir", "Writes.", "https://hc.test/weir.jpg")
private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")

private fun liveAnswer(): String =
    checkNotNull(HardcoverCatalogReadsTest::class.java.getResource("/hardcover/book-details.json")) {
        "missing fixture book-details.json — copy it from Task 0"
    }.readText()

private fun answering(body: String) =
    HardcoverGraphQlClient(
        HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }),
        apiBaseUrl = "https://hc.test",
    )

/** The catalogue reads #1542 adds: a book's details and its authors, decoded the way Hardcover answers. */
class HardcoverCatalogReadsTest :
    FunSpec({
        test("a book's details decode from Hardcover's own answer (captured live in Task 0)") {
            runTest {
                val details =
                    answering(liveAnswer())
                        .bookDetails("token", 427_578L)
                        .shouldBeInstanceOf<HardcoverCall.Ok<HardcoverBookDetails?>>()
                        .value
                        .shouldNotBeNull()

                details.hcBookId shouldBe 427_578L
                details.description.shouldNotBeNull()
                details.genres.shouldNotBeEmpty()
                details.moods.shouldNotBeEmpty()
                (details.genres + details.moods).all { it.count > 0 } shouldBe true
                details.authors.map { it.name } shouldContain "Andy Weir"
                details.authors.none { it.name == "Ray Porter" } shouldBe true
            }
        }

        test("only authors are authors: a narrator's credit is left out") {
            runTest {
                val hardcover =
                    FakeHardcoverCatalog().apply {
                        add(
                            FakeHardcoverCatalog.Book(
                                id = 1L,
                                title = "Project Hail Mary",
                                authors = listOf(WEIR),
                                narrators = listOf(FakeHardcoverCatalog.Author(8L, "Ray Porter")),
                            ),
                        )
                    }

                val details =
                    hardcover
                        .client()
                        .bookDetails("t", 1L)
                        .shouldBeInstanceOf<HardcoverCall.Ok<HardcoverBookDetails?>>()
                        .value!!

                details.authors shouldBe listOf(WEIR_PROFILE)
            }
        }

        test("an unknown book is Ok(null), not a failure") {
            runTest {
                FakeHardcoverCatalog().client().bookDetails("t", 99L) shouldBe HardcoverCall.Ok(null)
            }
        }

        test("cached_tags read the same whether Hardcover sends an object or a string holding one") {
            val asObject = Json.parseToJsonElement("""{"Mood":[{"tag":"hopeful","count":12}]}""")
            val asString = JsonPrimitive("""{"Mood":[{"tag":"hopeful","count":12}]}""")

            cachedTagsIn(asObject, MOOD_CATEGORY) shouldBe listOf(HardcoverTag("hopeful", 12))
            cachedTagsIn(asString, MOOD_CATEGORY) shouldBe listOf(HardcoverTag("hopeful", 12))
            cachedTagsIn(null, MOOD_CATEGORY) shouldBe emptyList()
        }

        test("a tag with no label is skipped, and a tag with no count counts zero") {
            val tags = Json.parseToJsonElement("""{"Genre":[{"count":3},{"tag":" Fantasy "}]}""")

            cachedTagsIn(tags, GENRE_CATEGORY) shouldBe listOf(HardcoverTag("Fantasy", 0))
        }

        test("a whole-number series position reads without a decimal; a half keeps it; none is none") {
            HardcoverSeriesPlacement(1L, "Bobiverse", 1.0).sequence shouldBe "1"
            HardcoverSeriesPlacement(1L, "Bobiverse", 1.5).sequence shouldBe "1.5"
            HardcoverSeriesPlacement(1L, "Bobiverse", null).sequence.shouldBeNull()
        }

        test("authors are found by exact name and by id") {
            runTest {
                val client =
                    FakeHardcoverCatalog()
                        .apply { add(FakeHardcoverCatalog.Book(1L, "Project Hail Mary", listOf(WEIR))) }
                        .client()

                client.authorsNamed("t", "Andy Weir") shouldBe
                    HardcoverCall.Ok(listOf(WEIR_PROFILE))
                client.authorsNamed("t", "Andy Weird") shouldBe HardcoverCall.Ok(emptyList())
                client.authorById("t", 7L) shouldBe HardcoverCall.Ok(WEIR_PROFILE)
            }
        }

        test("a refused token is Unauthorized, so the catalogue token can mark it and fall back") {
            runTest {
                val hardcover = FakeHardcoverCatalog().apply { rejected = setOf("expired") }
                hardcover.client().bookDetails("expired", 1L) shouldBe HardcoverCall.Unauthorized
            }
        }

        test("the details query asks for exactly the fields Task 0 confirmed") {
            runTest {
                var query = ""
                val client =
                    HardcoverGraphQlClient(
                        HttpClient(
                            MockEngine { request ->
                                val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                                query =
                                    Json
                                        .parseToJsonElement(body)
                                        .jsonObject
                                        .getValue("query")
                                        .toString()
                                respond("""{"data":{"books":[]}}""", HttpStatusCode.OK, JSON_HEADERS)
                            },
                        ),
                        apiBaseUrl = "https://hc.test",
                    )

                client.bookDetails("t", 1L)

                listOf("description", "cached_tags", "book_series", "position", "contributions", "contribution", "bio", "image")
                    .forEach { field -> (field in query) shouldBe true }
            }
        }
    })
