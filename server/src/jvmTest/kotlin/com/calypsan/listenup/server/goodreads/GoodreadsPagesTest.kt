package com.calypsan.listenup.server.goodreads

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private fun goodreadsFixture(name: String): String =
    checkNotNull(GoodreadsPagesTest::class.java.getResource("/goodreads/$name")) { "missing fixture $name" }.readText()

/** The real page's own aggregateRating block, verbatim: the mutations below are made from it. */
private const val CAPTURED_RATING = """"ratingValue":4.51,"ratingCount":1886867"""

class GoodreadsPagesTest :
    FunSpec({
        val bookPage = goodreadsFixture("book-page.html")

        test("a captured book page gives the rating its JSON-LD states") {
            GoodreadsPages.aggregateRating(bookPage) shouldBe GoodreadsRating(average = 4.51, count = 1_886_867)
        }

        test("a page whose JSON-LD lost its aggregateRating gives no rating") {
            GoodreadsPages.aggregateRating(goodreadsFixture("book-page-layout-changed.html")) shouldBe null
        }

        test("a rating value with no rating count gives no rating, never half a number") {
            val countless = bookPage.replace(CAPTURED_RATING, """"ratingValue":4.51""")
            check(countless != bookPage) { "the mutation did not apply" }

            GoodreadsPages.aggregateRating(countless) shouldBe null
        }

        test("a rating value written as a string still reads") {
            val stringly = bookPage.replace(CAPTURED_RATING, """"ratingValue":"4.51","ratingCount":"1886867"""")
            check(stringly != bookPage) { "the mutation did not apply" }

            GoodreadsPages.aggregateRating(stringly) shouldBe GoodreadsRating(average = 4.51, count = 1_886_867)
        }

        test("a page with no JSON-LD at all gives no rating") {
            GoodreadsPages.aggregateRating(goodreadsFixture("search-results.html")) shouldBe null
        }

        test("a captured search page gives every result's book path, title and author, in Goodreads' order") {
            val results = GoodreadsPages.searchResults(goodreadsFixture("search-results.html"))

            results shouldHaveSize 15
            results.first() shouldBe
                GoodreadsCandidate(
                    bookPath = "/book/show/132754948-the-best-christmas-pageant-ever-author",
                    title = "[(The Best Christmas Pageant Ever )] [Author: Barbara Robinson] [Sep-1988]",
                    author = "Barbara Robinson",
                )
            results[13] shouldBe
                GoodreadsCandidate(
                    bookPath = "/book/show/123632831-the-best-christmas-pageant-ever",
                    title = "The Best Christmas Pageant Ever",
                    author = "Barbara Robinson",
                )
            results[8].title shouldBe
                "The Best Barbara Robinson 3-in-1 Treasury Ever! ...The Best Christmas Pageant Ever, " +
                "The Best School Year Ever, & My Brother Louis Measures Worms and Other Louis Stories"
        }

        test("a book page is no search page") {
            GoodreadsPages.searchResults(bookPage) shouldBe emptyList()
        }
    })
