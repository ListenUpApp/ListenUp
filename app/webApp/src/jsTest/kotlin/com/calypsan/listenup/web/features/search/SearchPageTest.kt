package com.calypsan.listenup.web.features.search

import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.presentation.search.SearchUiState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

private fun searchPage(
    state: SearchUiState,
    onQueryChanged: (String) -> Unit = {},
    onToggleType: (SearchHitType) -> Unit = {},
    onOpenHit: (SearchHit) -> Unit = {},
    onRetry: () -> Unit = {},
): HTMLElement {
    val root = document.createElement("div") as HTMLElement
    document.body?.appendChild(root)
    renderComposable(root = root) {
        SearchPage(
            state = state,
            onQueryChanged = onQueryChanged,
            onToggleType = onToggleType,
            onOpenHit = onOpenHit,
            onRetry = onRetry,
        )
    }
    return root
}

private fun EventTarget.press(key: String) {
    dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))
}

/**
 * The Search page rendered against a fixed session (Task 1 — no routing yet).
 *
 * What these pin: each of the five [SearchUiState] variants renders a marker the others do not —
 * [SearchUiState.TooShort] vs. zero-hit [SearchUiState.Results] is the pair that matters most,
 * because the whole reason `TooShort` exists is that rendering it as "no results" makes a
 * two-letter query look like a broken app (see the KDoc on `SearchUiState.TooShort`). A hit row
 * and a type chip stay reachable by keyboard, a click or Enter/Space reports the right value, and
 * nothing on the page fabricates a field a hit doesn't actually carry.
 */
class SearchPageTest :
    FunSpec({

        test("Idle renders its own marker, and no other state's marker") {
            val root = searchPage(state = SearchUiState.Idle())

            root.querySelector(".is-idle") shouldNotBe null
            root.querySelector(".is-tooshort") shouldBe null
            root.querySelector(".is-searching") shouldBe null
            root.querySelector(".is-error") shouldBe null
            root.querySelector(".is-noresults") shouldBe null
            root.querySelector(".search-results") shouldBe null
        }

        test("TooShort renders its own marker, never the no-results marker") {
            val root = searchPage(state = SearchUiState.TooShort(query = "du", selectedTypes = emptySet()))

            root.querySelector(".is-tooshort") shouldNotBe null
            root.querySelector(".is-idle") shouldBe null
            root.querySelector(".is-noresults") shouldBe null
            root.querySelector(".is-error") shouldBe null
            root.querySelector(".search-results") shouldBe null
        }

        test("a zero-hit Results is its own case, distinct from Idle and TooShort") {
            val root =
                searchPage(
                    state =
                        SearchUiState.Results(
                            query = "zzzzz",
                            selectedTypes = emptySet(),
                            result = searchResult(query = "zzzzz", hits = emptyList()),
                        ),
                )

            root.querySelector(".is-noresults") shouldNotBe null
            root.querySelector(".is-idle") shouldBe null
            root.querySelector(".is-tooshort") shouldBe null
            root.querySelector(".is-error") shouldBe null
            root.querySelector(".search-results") shouldBe null
        }

        test("Searching renders its own marker, and does not claim there are no results") {
            val root = searchPage(state = SearchUiState.Searching(query = "dun", selectedTypes = emptySet()))

            root.querySelector(".is-searching") shouldNotBe null
            root.querySelector(".is-noresults") shouldBe null
            root.querySelector(".is-tooshort") shouldBe null
            root.querySelector(".search-results") shouldBe null
        }

        test("Error renders its own marker with the message and a retry affordance") {
            val root =
                searchPage(
                    state =
                        SearchUiState.Error(
                            query = "dune",
                            selectedTypes = emptySet(),
                            message = "Search unavailable.",
                        ),
                )

            root.querySelector(".is-error") shouldNotBe null
            (root.querySelector(".is-error") as HTMLElement).textContent!! shouldContain "Search unavailable."
            root.querySelector(".is-error .btn-o") shouldNotBe null
        }

        test("retry fires the reported gesture") {
            var retried = 0
            val root =
                searchPage(
                    state = SearchUiState.Error(query = "dune", selectedTypes = emptySet(), message = "oops"),
                    onRetry = { retried++ },
                )

            (root.querySelector(".is-error .btn-o") as HTMLElement).click()

            retried shouldBe 1
        }

        test("Results groups hits by type and shows each one's own facts") {
            val root =
                searchPage(
                    state =
                        SearchUiState.Results(
                            query = "dune",
                            selectedTypes = emptySet(),
                            result =
                                searchResult(
                                    query = "dune",
                                    hits =
                                        listOf(
                                            bookHit("b1", "Dune", author = "Frank Herbert", duration = 63_000_000L),
                                            contributorHit("c1", "Frank Herbert"),
                                        ),
                                ),
                        ),
                )

            root.querySelectorAll(".search-row").length shouldBe 2
            root.textContent!! shouldContain "Dune"
            (root.querySelector(".search-row .search-meta") as HTMLElement).textContent!! shouldContain "Frank Herbert"
        }

        test("a contributor hit with no extra fields shows only its name, never a fabricated line") {
            val root =
                searchPage(
                    state =
                        SearchUiState.Results(
                            query = "weir",
                            selectedTypes = emptySet(),
                            result = searchResult(query = "weir", hits = listOf(contributorHit("c1", "Andy Weir"))),
                        ),
                )

            val row = root.querySelector(".search-row") as HTMLElement
            row.textContent!! shouldContain "Andy Weir"
            row.querySelector(".search-meta") shouldBe null
        }

        test("a hit row click reports the hit's own id and type") {
            val opened = mutableListOf<SearchHit>()
            val hit = bookHit("b1", "Dune")
            val root =
                searchPage(
                    state =
                        SearchUiState.Results(
                            query = "dune",
                            selectedTypes = emptySet(),
                            result = searchResult(query = "dune", hits = listOf(hit)),
                        ),
                    onOpenHit = { opened += it },
                )

            (root.querySelector(".search-row") as HTMLElement).click()

            opened shouldBe listOf(hit)
        }

        test("Enter and Space each activate a hit row, matching the kit's keyboard contract") {
            val hit = bookHit("b1", "Dune")

            fun page(opened: MutableList<SearchHit>) =
                searchPage(
                    state =
                        SearchUiState.Results(
                            query = "dune",
                            selectedTypes = emptySet(),
                            result = searchResult(query = "dune", hits = listOf(hit)),
                        ),
                    onOpenHit = { opened += it },
                )

            val enterOpened = mutableListOf<SearchHit>()
            (page(enterOpened).querySelector(".search-row") as HTMLElement).press("Enter")
            enterOpened shouldBe listOf(hit)

            val spaceOpened = mutableListOf<SearchHit>()
            (page(spaceOpened).querySelector(".search-row") as HTMLElement).press(" ")
            spaceOpened shouldBe listOf(hit)
        }

        test("a hit row is reachable by keyboard and announces itself as a control") {
            val root =
                searchPage(
                    state =
                        SearchUiState.Results(
                            query = "dune",
                            selectedTypes = emptySet(),
                            result = searchResult(query = "dune", hits = listOf(bookHit("b1", "Dune"))),
                        ),
                )

            val row = root.querySelector(".search-row") as HTMLElement
            row.getAttribute("tabindex") shouldBe "0"
            row.getAttribute("role") shouldBe "button"
        }

        test("typing in the field reports the query") {
            var captured: String? = null
            val root = searchPage(state = SearchUiState.Idle(), onQueryChanged = { captured = it })

            val input = root.querySelector(".f-input") as HTMLInputElement
            input.value = "dune"
            input.dispatchEvent(Event("input", EventInit(bubbles = true)))

            captured shouldBe "dune"
        }

        test("a type chip toggle reports the right SearchHitType") {
            val toggled = mutableListOf<SearchHitType>()
            val root = searchPage(state = SearchUiState.Idle(), onToggleType = { toggled += it })

            root.querySelectorAll(".pill").let { chips ->
                (0 until chips.length)
                    .map { chips.item(it) as HTMLElement }
                    .first { it.textContent == "Contributors" }
                    .click()
            }

            toggled shouldBe listOf(SearchHitType.CONTRIBUTOR)
        }

        test("a selected type chip carries the selected class") {
            val root = searchPage(state = SearchUiState.Idle(query = "", selectedTypes = setOf(SearchHitType.SERIES)))

            root.querySelectorAll(".pill").let { chips ->
                val series =
                    (0 until chips.length).map { chips.item(it) as HTMLElement }.first { it.textContent == "Series" }
                series.classList.contains("on") shouldBe true
            }
        }

        test("an offline result says so, honestly") {
            val root =
                searchPage(
                    state =
                        SearchUiState.Results(
                            query = "dune",
                            selectedTypes = emptySet(),
                            result =
                                searchResult(
                                    query = "dune",
                                    hits = listOf(bookHit("b1", "Dune")),
                                    isOfflineResult = true,
                                ),
                        ),
                )

            root.querySelector(".banner.info") shouldNotBe null
        }

        test("a non-offline result carries no offline banner") {
            val root =
                searchPage(
                    state =
                        SearchUiState.Results(
                            query = "dune",
                            selectedTypes = emptySet(),
                            result = searchResult(query = "dune", hits = listOf(bookHit("b1", "Dune"))),
                        ),
                )

            root.querySelector(".banner.info") shouldBe null
        }
    })
