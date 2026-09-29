package com.calypsan.listenup.server.goodreads

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.ExternalRatingMeta
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList

private val HTML = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")
private val LOCALE = MetadataLocale("en", "us")

private fun pageFixture(name: String): String =
    checkNotNull(GoodreadsRatingSourceTest::class.java.getResource("/goodreads/$name")) { "missing fixture $name" }.readText()

private val BOOK_PAGE = pageFixture("book-page.html")
private val LAYOUT_CHANGED = pageFixture("book-page-layout-changed.html")
private val SEARCH_RESULTS = pageFixture("search-results.html")
private val SEARCH_MATCH_PAGE = pageFixture("search-match-page.html")

private val HAIL_MARY = BookIdentity(isbn = "9780593135204", title = "Project Hail Mary", primaryAuthor = "Andy Weir")
private val PAGEANT = BookIdentity(title = "The Best Christmas Pageant Ever", primaryAuthor = "Barbara Robinson")

/** A scripted goodreads.com: one canned reply per page kind, and a log of every path and query asked for. */
private class FakeGoodreads {
    val asked = CopyOnWriteArrayList<String>()
    var byIsbn: Pair<HttpStatusCode, String> = HttpStatusCode.OK to BOOK_PAGE
    var search: Pair<HttpStatusCode, String> = HttpStatusCode.OK to SEARCH_RESULTS
    var bookShow: Pair<HttpStatusCode, String> = HttpStatusCode.OK to SEARCH_MATCH_PAGE
    val userAgents = CopyOnWriteArrayList<String>()

    val client =
        GoodreadsClient(
            http =
                HttpClient(
                    MockEngine { req ->
                        val path = req.url.encodedPath
                        asked += if (path == "/search") "$path?q=${req.url.parameters["q"]}" else path
                        userAgents += req.headers[HttpHeaders.UserAgent].orEmpty()
                        val reply =
                            when {
                                path.startsWith("/book/isbn/") -> byIsbn
                                path == "/search" -> search
                                else -> bookShow
                            }
                        respond(reply.second, reply.first, HTML)
                    },
                ),
            baseUrl = "https://gr.test",
        )
}

private class NoWait : GoodreadsRateLimiter() {
    override suspend fun await() = Unit
}

private fun source(fake: FakeGoodreads) = GoodreadsRatingSource(client = fake.client, rateLimiter = NoWait())

class GoodreadsRatingSourceTest :
    FunSpec({
        test("a book with an ISBN reads the rating off its Goodreads page, with no region") {
            val fake = FakeGoodreads()

            val result = source(fake).getRating(HAIL_MARY, LOCALE)

            result shouldBe AppResult.Success(ExternalRatingMeta(average = 4.51, count = 1_886_867, region = null))
            fake.asked shouldBe listOf("/book/isbn/9780593135204")
            fake.userAgents.single() shouldContain "Mozilla/5.0"
        }

        test("a page whose format changed is a Malformed failure, never a number") {
            val fake = FakeGoodreads().apply { byIsbn = HttpStatusCode.OK to LAYOUT_CHANGED }

            val result = source(fake).getRating(HAIL_MARY, LOCALE)

            val failure = result.shouldBeInstanceOf<AppResult.Failure>()
            val error = failure.error.shouldBeInstanceOf<MetadataError.Malformed>()
            error.debugInfo.orEmpty() shouldContain "page format changed"
        }

        listOf(HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable).forEach { status ->
            test("Goodreads refusing the request with ${status.value} is an ExternalUnavailable failure") {
                val fake = FakeGoodreads().apply { byIsbn = status to "" }

                val result = source(fake).getRating(HAIL_MARY, LOCALE)

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                val error = failure.error.shouldBeInstanceOf<MetadataError.ExternalUnavailable>()
                error.debugInfo.orEmpty() shouldContain "refused"
            }
        }

        test("an ISBN Goodreads does not know falls back to the title search") {
            val fake = FakeGoodreads().apply { byIsbn = HttpStatusCode.NotFound to "" }

            source(fake).getRating(PAGEANT.copy(isbn = "9798999999990"), LOCALE)

            fake.asked.first() shouldBe "/book/isbn/9798999999990"
            fake.asked[1] shouldBe "/search?q=The Best Christmas Pageant Ever Barbara Robinson"
        }

        test("with no ISBN, the best confident title match's page gives the rating") {
            val fake = FakeGoodreads()

            val result = source(fake).getRating(PAGEANT, LOCALE)

            // The exact title by the exact author, ahead of the higher-ranked script adaptation and
            // study guides, whose titles only share words with it.
            fake.asked shouldBe
                listOf(
                    "/search?q=The Best Christmas Pageant Ever Barbara Robinson",
                    "/book/show/123632831-the-best-christmas-pageant-ever",
                )
            result shouldBe AppResult.Success(ExternalRatingMeta(average = 5.0, count = 3))
        }

        test("no confident title match is a confident no rating, with no book page fetched") {
            val fake = FakeGoodreads()

            val result = source(fake).getRating(BookIdentity(title = "Dune", primaryAuthor = "Frank Herbert"), LOCALE)

            result shouldBe AppResult.Success(null)
            fake.asked shouldBe listOf("/search?q=Dune Frank Herbert")
        }

        test("a book with neither an ISBN nor an author asks Goodreads nothing") {
            val fake = FakeGoodreads()

            source(fake).getRating(BookIdentity(title = "The Best Christmas Pageant Ever"), LOCALE) shouldBe
                AppResult.Success(null)
            fake.asked shouldBe emptyList()
        }

        test("an unreachable Goodreads is an ExternalUnavailable failure") {
            val fake = FakeGoodreads().apply { search = HttpStatusCode.BadGateway to "" }

            val result = source(fake).getRating(PAGEANT, LOCALE)

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<MetadataError.ExternalUnavailable>()
        }
    })
