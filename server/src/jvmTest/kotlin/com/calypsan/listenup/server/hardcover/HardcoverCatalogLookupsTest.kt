package com.calypsan.listenup.server.hardcover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest

private fun lookupFixture(name: String): String =
    checkNotNull(HardcoverCatalogLookupsTest::class.java.getResource("/hardcover/$name")) { "missing fixture $name" }.readText()

private class RecordingCatalog(
    private val reply: String,
    private val status: HttpStatusCode = HttpStatusCode.OK,
) {
    var sentBody = ""
    val client =
        HardcoverGraphQlClient(
            HttpClient(
                MockEngine { req ->
                    sentBody = (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(reply, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
            "https://hc.test",
        )
}

/** The catalog lookups matching and manual linking read: ids, editions, every author, and search. */
class HardcoverCatalogLookupsTest :
    FunSpec({

        test("editionByAsin returns the edition, its reading format and its book with ids and authors") {
            runTest {
                val catalog = RecordingCatalog(lookupFixture("rating-by-asin-found.json"))
                val hit =
                    catalog.client
                        .editionByAsin("hc_at_1", "B08G9RZBTT")
                        .shouldBeInstanceOf<HardcoverCall.Ok<HardcoverEditionHit?>>()
                        .value!!
                hit.book.id shouldBe 427_578L
                hit.book.authors shouldBe listOf("Andy Weir")
                catalog.sentBody shouldContain "asin:{_eq"
            }
        }

        test("editionByIsbn asks for ISBN-13 or ISBN-10 and says a paperback edition is not the audiobook") {
            runTest {
                val catalog = RecordingCatalog(lookupFixture("edition-by-isbn-paperback.json"))
                val hit =
                    catalog.client
                        .editionByIsbn("hc_at_1", "0593135202")
                        .shouldBeInstanceOf<HardcoverCall.Ok<HardcoverEditionHit?>>()
                        .value!!
                hit.editionId shouldBe 31_415L
                hit.isAudiobook shouldBe false
                hit.book.defaultAudioEditionId shouldBe 9_001L
                catalog.sentBody shouldContain "isbn_13"
                catalog.sentBody shouldContain "isbn_10"
            }
        }

        test("an edition lookup with no edition is Ok(null), not a failure") {
            runTest {
                RecordingCatalog(lookupFixture("rating-by-asin-missing.json")).client.editionByAsin("hc_at_1", "X") shouldBe
                    HardcoverCall.Ok(null)
            }
        }

        test("booksTitled lists every candidate with all its authors") {
            runTest {
                val books =
                    RecordingCatalog(lookupFixture("rating-by-title-author.json"))
                        .client
                        .booksTitled("hc_at_1", "The Best Christmas Pageant Ever")
                        .shouldBeInstanceOf<HardcoverCall.Ok<List<HardcoverCatalogBook>>>()
                        .value
                books.map { it.id } shouldBe listOf(229_211L, 317_024L, 761_573L, 2_812_044L)
                books.last().authors shouldBe emptyList()
            }
        }

        test("searchBooks sends one search and reads hits whose id is a string or a number") {
            runTest {
                val catalog = RecordingCatalog(lookupFixture("search-hail-mary.json"))
                val hits =
                    catalog.client
                        .searchBooks("hc_at_1", "Project Hail Mary")
                        .shouldBeInstanceOf<HardcoverCall.Ok<List<HardcoverSearchHit>>>()
                        .value
                hits.map { it.bookId } shouldBe listOf(427_578L, 1_234_567L)
                hits.first().authors shouldBe listOf("Andy Weir")
                catalog.sentBody shouldContain "search(query:"
            }
        }

        test("booksByIds returns each book's default audiobook edition") {
            runTest {
                val books =
                    RecordingCatalog(lookupFixture("books-by-ids.json"))
                        .client
                        .booksByIds("hc_at_1", listOf(427_578L, 1_234_567L))
                        .shouldBeInstanceOf<HardcoverCall.Ok<List<HardcoverCatalogBook>>>()
                        .value
                books.single { it.id == 427_578L }.defaultAudioEditionId shouldBe 9_001L
                books.single { it.id == 427_578L }.authors shouldBe listOf("Andy Weir", "Ray Porter")
            }
        }

        test("a lookup's failure is classified, not swallowed") {
            runTest {
                RecordingCatalog("{}", HttpStatusCode.TooManyRequests).client.editionByAsin("hc_at_1", "X") shouldBe
                    HardcoverCall.Throttled(null)
            }
        }
    })
