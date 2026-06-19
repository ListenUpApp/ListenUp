package com.calypsan.listenup.server.metadata.audible

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [parseContributorSearch] — extracts the contributor hits from an Audible
 * search-by-author page (`/search?searchAuthor=...`).
 *
 * Fixtures mirror the real anchor shape rather than full page snapshots. The key case is #551:
 * Audible appends tracking query params (`?ref=…`, `pf_rd_*`, …) after the ASIN, which the previous
 * regex (requiring a quote immediately after the ASIN) failed to match, making every search empty.
 */
class ParseContributorSearchTest :
    FunSpec({
        test("extracts ASIN and name when the href carries tracking query params (#551)") {
            val html =
                """
                <li><a href="/author/Brandon-Sanderson/B001IGFHW6?ref_pageloadid=not_applicable&pf_rd_p=83218cca&ref=a_search_c3_lAuthor_1_2_1">Brandon Sanderson</a></li>
                """.trimIndent()

            val results = parseContributorSearch(html)

            results.size shouldBe 1
            results.single().asin shouldBe "B001IGFHW6"
            results.single().name shouldBe "Brandon Sanderson"
        }

        test("still parses a plain href with no query params") {
            val html = """<a href="/author/Frank-Herbert/B000APZOQA">Frank Herbert</a>"""

            val results = parseContributorSearch(html)

            results.single().asin shouldBe "B000APZOQA"
            results.single().name shouldBe "Frank Herbert"
        }

        test("deduplicates an author that repeats across product listings, preserving order") {
            val html =
                """
                <a href="/author/Tim-Ferriss/B001ILKBW2?ref=a">Tim Ferriss</a>
                <a href="/author/James-Clear/B07D23CFGR?ref=b">James Clear</a>
                <a href="/author/Tim-Ferriss/B001ILKBW2?ref=c">Tim Ferriss</a>
                """.trimIndent()

            parseContributorSearch(html).map { it.asin } shouldContainExactly
                listOf("B001ILKBW2", "B07D23CFGR")
        }

        test("returns an empty list when no author links are present") {
            parseContributorSearch("<html><body><p>No results</p></body></html>") shouldBe emptyList()
        }
    })
