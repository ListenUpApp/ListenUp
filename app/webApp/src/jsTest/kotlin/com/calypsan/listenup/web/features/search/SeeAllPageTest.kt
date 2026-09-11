package com.calypsan.listenup.web.features.search

import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.presentation.search.SearchResultCaps
import com.calypsan.listenup.client.presentation.search.SeeAllSearchUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private fun text(
    host: HTMLElement,
    selector: String,
): String? = (host.querySelector(selector) as? HTMLElement)?.textContent?.trim()

private fun rowNames(host: HTMLElement): List<String> =
    host
        .querySelectorAll(".search-name")
        .asList()
        .filterIsInstance<HTMLElement>()
        .map { it.textContent.orEmpty().trim() }

private fun seeAllButtons(host: HTMLElement): List<HTMLButtonElement> =
    host.querySelectorAll(".search-seeall").asList().filterIsInstance<HTMLButtonElement>()

/**
 * The capped groups on `/search` and the uncapped page they defer to.
 *
 * ⛔ The pair is the point. The main search runs with a limit of 30 across every type; before this,
 * web rendered whatever came back with no cap and no hint — so a query with forty book matches
 * showed an arbitrary subset that read as the complete answer. Capping alone would only hide more;
 * the see-all page re-runs the same query for one type with a limit of 100, which is what makes
 * the cap honest.
 */
class SeeAllPageTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun page(
            state: SeeAllSearchUiState,
            onOpenHit: (SearchHit) -> Unit = {},
            onOpenSearch: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                SeeAllPage(
                    state = state,
                    openableTypes = SearchHitType.entries.toSet(),
                    onOpenHit = onOpenHit,
                    onOpenSearch = onOpenSearch,
                )
            }

        test("the caps are the shared ones, so three platforms cannot disagree about list length") {
            // ⛔ Read from SearchResultCaps rather than redeclared. A web-only constant would drift
            // from Android and iOS invisibly — every platform would look self-consistent.
            capFor(SearchHitType.BOOK) shouldBe SearchResultCaps.BOOK
            capFor(SearchHitType.CONTRIBUTOR) shouldBe SearchResultCaps.CONTRIBUTOR
            capFor(SearchHitType.SERIES) shouldBe SearchResultCaps.SERIES
        }

        test("tags are never capped — they are pills, and a dozen cost a line") {
            capFor(SearchHitType.TAG).shouldBeNull()
        }

        test("a capped group shows the cap and offers the rest") {
            val hits = (1..9).map { bookHit("b$it", "Book $it") }
            val host =
                mounts.mount {
                    ResultsList(
                        result = searchResult(query = "book", hits = hits),
                        openableTypes = SearchHitType.entries.toSet(),
                        onOpenHit = {},
                        onSeeAll = {},
                    )
                }

            rowNames(host).size shouldBe SearchResultCaps.BOOK
            // The count in the header is the whole group, not the rendered slice — that disagreement
            // is exactly what tells the reader something is being held back.
            text(host, ".search-group-count") shouldBe "9"
            seeAllButtons(host).size shouldBe 1
        }

        test("an uncapped group offers nothing, because nothing is being withheld") {
            // ⛔ An always-present "See all" over a complete list is a promise of more that is not
            // there — worse than no affordance at all.
            val host =
                mounts.mount {
                    ResultsList(
                        result = searchResult(query = "dune", hits = listOf(bookHit("b1", "Dune"))),
                        openableTypes = SearchHitType.entries.toSet(),
                        onOpenHit = {},
                        onSeeAll = {},
                    )
                }

            rowNames(host) shouldContainExactly listOf("Dune")
            seeAllButtons(host).size shouldBe 0
        }

        test("See all reports which group it was pressed on") {
            val asked = mutableListOf<SearchHitType>()
            val hits = (1..9).map { contributorHit("c$it", "Person $it") }
            val host =
                mounts.mount {
                    ResultsList(
                        result = searchResult(query = "p", hits = hits),
                        openableTypes = SearchHitType.entries.toSet(),
                        onOpenHit = {},
                        onSeeAll = { asked += it },
                    )
                }

            seeAllButtons(host).single().click()
            awaitFrame()

            asked shouldContainExactly listOf(SearchHitType.CONTRIBUTOR)
        }

        test("the see-all page lists every hit, uncapped") {
            val hits = (1..12).map { bookHit("b$it", "Book $it") }
            val host = page(SeeAllSearchUiState.Results(SearchHitType.BOOK, "book", hits))

            rowNames(host).size shouldBe 12
            text(host, ".sall-t") shouldBe "Books"
            text(host, ".sall-n") shouldBe "12 results"
            text(host, ".sall-q") shouldBe "for “book”"
        }

        test("one result is a result, not 1 results") {
            val host = page(SeeAllSearchUiState.Results(SearchHitType.SERIES, "cosmere", listOf(seriesHit("s1", "Mistborn"))))

            text(host, ".sall-n") shouldBe "1 result"
        }

        test("opening a hit from the see-all page reports it") {
            val opened = mutableListOf<String>()
            val hit = bookHit("b7", "Best Served Cold")
            val host = page(SeeAllSearchUiState.Results(SearchHitType.BOOK, "cold", listOf(hit)), onOpenHit = { opened += it.id })

            (host.querySelector(".search-row") as HTMLElement).click()
            awaitFrame()

            opened shouldContainExactly listOf("b7")
        }

        test("the trail leads back to the search it expanded") {
            var back = 0
            val host =
                page(
                    SeeAllSearchUiState.Results(SearchHitType.BOOK, "dune", listOf(bookHit("b1", "Dune"))),
                    onOpenSearch = { back++ },
                )

            (host.querySelector(".crumb a") as HTMLElement).textContent?.trim() shouldBe "Search"
            (host.querySelector(".crumb a") as HTMLElement).click()
            awaitFrame()

            back shouldBe 1
        }

        test("a state with no type yet does not invent one for the trail") {
            // Idle/Loading/TooShort carry no type — the query is in the URL but the state has not
            // settled — so the crumb says what the page is rather than guessing.
            seeAllTitle(SeeAllSearchUiState.Loading) shouldBe "All results"
            seeAllTitle(SeeAllSearchUiState.Results(SearchHitType.CONTRIBUTOR, "q", emptyList())) shouldBe "Contributors"
        }

        test("every non-result state says which one it is") {
            page(SeeAllSearchUiState.Loading).querySelector(".is-searching").shouldNotBeNull()
            page(SeeAllSearchUiState.Idle).querySelector(".is-searching").shouldNotBeNull()
            page(SeeAllSearchUiState.TooShort).querySelector(".is-tooshort").shouldNotBeNull()
            page(SeeAllSearchUiState.Error("Search unavailable.")).querySelector(".is-error").shouldNotBeNull()
            page(SeeAllSearchUiState.Results(SearchHitType.BOOK, "zzz", emptyList()))
                .querySelector(".is-noresults")
                .shouldNotBeNull()
        }
    })
