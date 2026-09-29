package com.calypsan.listenup.server.goodreads

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun goodreadsFixture(name: String): String =
    checkNotNull(GoodreadsPagesTest::class.java.getResource("/goodreads/$name")) { "missing fixture $name" }.readText()

/** The real page's own aggregateRating block, verbatim: the mutations below are made from it. */
private const val CAPTURED_RATING = """"ratingValue":4.51,"ratingCount":1886867"""

class GoodreadsPagesTest :
    FunSpec({
        val bookPage = goodreadsFixture("book-page.html")

        test("a captured book page gives the rating its JSON-LD states") {
            GoodreadsPages.bookRating(bookPage) shouldBe GoodreadsBookRating.Rated(average = 4.51, count = 1_886_867)
        }

        test("a captured page of a book nobody has rated reads as unrated") {
            GoodreadsPages.bookRating(goodreadsFixture("book-page-unrated.html")) shouldBe GoodreadsBookRating.Unrated
        }

        test("a book page with no JSON-LD Book at all is unrecognised") {
            GoodreadsPages.bookRating(goodreadsFixture("book-page-layout-changed.html")) shouldBe
                GoodreadsBookRating.Unrecognised
        }

        test("a rating value with no rating count is unrecognised, never half a number") {
            val countless = bookPage.replace(CAPTURED_RATING, """"ratingValue":4.51""")
            check(countless != bookPage) { "the mutation did not apply" }

            GoodreadsPages.bookRating(countless) shouldBe GoodreadsBookRating.Unrecognised
        }

        test("a rating value that is no number is unrecognised") {
            val garbled = bookPage.replace(CAPTURED_RATING, """"ratingValue":"four","ratingCount":1886867""")
            check(garbled != bookPage) { "the mutation did not apply" }

            GoodreadsPages.bookRating(garbled) shouldBe GoodreadsBookRating.Unrecognised
        }

        test("a rating value written as a string still reads") {
            val stringly = bookPage.replace(CAPTURED_RATING, """"ratingValue":"4.51","ratingCount":"1886867"""")
            check(stringly != bookPage) { "the mutation did not apply" }

            GoodreadsPages.bookRating(stringly) shouldBe GoodreadsBookRating.Rated(average = 4.51, count = 1_886_867)
        }

        test("a search page is no book page") {
            GoodreadsPages.bookRating(goodreadsFixture("search-results.html")) shouldBe GoodreadsBookRating.Unrecognised
        }

        test("a captured search page gives every result's book path, title and author, in Goodreads' order") {
            val results =
                GoodreadsPages
                    .searchResults(goodreadsFixture("search-results.html"))
                    .shouldBeInstanceOf<GoodreadsSearch.Results>()
                    .candidates

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

        test("a captured search that found nothing gives no results") {
            GoodreadsPages.searchResults(goodreadsFixture("search-no-results.html")) shouldBe
                GoodreadsSearch.Results(emptyList())
        }

        test("a search page whose result rows no longer parse is unrecognised, not empty") {
            val searchPage = goodreadsFixture("search-results.html")
            val renamed = searchPage.replace("<span itemprop='name'", "<span data-name='name'")
            check(renamed != searchPage) { "the mutation did not apply" }

            GoodreadsPages.searchResults(renamed) shouldBe GoodreadsSearch.Unrecognised
        }

        test("a book page is no search page") {
            GoodreadsPages.searchResults(bookPage) shouldBe GoodreadsSearch.Unrecognised
        }
    })
