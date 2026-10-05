package com.calypsan.listenup.web.features.search

import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.presentation.search.SearchUiState
import androidx.compose.runtime.CompositionLocalProvider
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.design.LocalRestrictedBookIds
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.EventInit
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

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
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun searchPage(
            state: SearchUiState,
            onQueryChanged: (String) -> Unit = {},
            onToggleType: (SearchHitType) -> Unit = {},
            onClearTypes: () -> Unit = {},
            onOpenHit: (SearchHit) -> Unit = {},
            onRetry: () -> Unit = {},
            openableTypes: Set<SearchHitType> = SearchHitType.entries.toSet(),
        ): HTMLElement =
            mounts.mount {
                SearchPage(
                    state = state,
                    onQueryChanged = onQueryChanged,
                    onToggleType = onToggleType,
                    onClearTypes = onClearTypes,
                    onOpenHit = onOpenHit,
                    onRetry = onRetry,
                    openableTypes = openableTypes,
                )
            }

        fun pills(root: HTMLElement): List<HTMLElement> =
            root.querySelectorAll(".pill").let { found ->
                (0 until found.length).map { found.item(it) as HTMLElement }
            }

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
            root.querySelector(".is-error .btn-secondary") shouldNotBe null
        }

        test("retry fires the reported gesture") {
            var retried = 0
            val root =
                searchPage(
                    state = SearchUiState.Error(query = "dune", selectedTypes = emptySet(), message = "oops"),
                    onRetry = { retried++ },
                )

            (root.querySelector(".is-error .btn-secondary") as HTMLElement).click()

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

        // Search still finds every series, so a sub-series' hit says where it sits.
        test("a sub-series hit says where it sits and how many books it holds") {
            val hit = seriesHit("s1", "Mistborn Era 1").copy(seriesPath = listOf("Cosmere", "Mistborn"), bookCount = 4)
            val root =
                searchPage(
                    state =
                        SearchUiState.Results(
                            query = "era",
                            selectedTypes = emptySet(),
                            result = searchResult(query = "era", hits = listOf(hit)),
                        ),
                )

            (root.querySelector(".search-row .search-meta") as HTMLElement).textContent shouldBe
                "in Cosmere › Mistborn · 4 books"
        }

        test("a top-level series hit shows just its book count") {
            val hit = seriesHit("s1", "Cosmere").copy(bookCount = 23)
            val root =
                searchPage(
                    state =
                        SearchUiState.Results(
                            query = "cos",
                            selectedTypes = emptySet(),
                            result = searchResult(query = "cos", hits = listOf(hit)),
                        ),
                )

            (root.querySelector(".search-row .search-meta") as HTMLElement).textContent shouldBe "23 books"
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

        test("All clears every type filter rather than toggling one more") {
            // ⛔ The gap this closes. Web had the toggles and no way back out of them: a reader who
            // had narrowed to Series had to remember which chips they pressed to widen again.
            var cleared = 0
            val toggled = mutableListOf<SearchHitType>()
            val root =
                searchPage(
                    state = SearchUiState.Idle(query = "", selectedTypes = setOf(SearchHitType.SERIES)),
                    onToggleType = { toggled += it },
                    onClearTypes = { cleared++ },
                )

            pills(root).first { it.textContent == "All" }.click()

            cleared shouldBe 1
            // Not a fifth type — All is the absence of a filter, so it must not report one.
            toggled shouldBe emptyList()
        }

        test("All reads as selected exactly when no type filter is set") {
            val unfiltered = searchPage(state = SearchUiState.Idle())
            pills(unfiltered).first { it.textContent == "All" }.classList.contains("on") shouldBe true

            val filtered =
                searchPage(state = SearchUiState.Idle(query = "", selectedTypes = setOf(SearchHitType.SERIES)))
            pills(filtered).first { it.textContent == "All" }.classList.contains("on") shouldBe false
        }

        test("All leads the row, because it is where a reader looks to widen again") {
            val root = searchPage(state = SearchUiState.Idle())

            pills(root).first().textContent shouldBe "All"
        }

        test("a selected type chip carries the selected class") {
            val root = searchPage(state = SearchUiState.Idle(query = "", selectedTypes = setOf(SearchHitType.SERIES)))

            root.querySelectorAll(".pill").let { chips ->
                val series =
                    (0 until chips.length).map { chips.item(it) as HTMLElement }.first { it.textContent == "Series" }
                series.classList.contains("on") shouldBe true
            }
        }

        test("a hit type with no destination renders non-interactively, never as a dead click") {
            // Only BOOK is in openableTypes here — CONTRIBUTOR has no route wired yet. Its row
            // must show its data but carry none of the button contract: no tabindex, no role, no
            // chevron promising a destination that doesn't exist, and a click must not fire.
            val opened = mutableListOf<SearchHit>()
            val root =
                searchPage(
                    state =
                        SearchUiState.Results(
                            query = "weir",
                            selectedTypes = emptySet(),
                            result = searchResult(query = "weir", hits = listOf(contributorHit("c1", "Andy Weir"))),
                        ),
                    onOpenHit = { opened += it },
                    openableTypes = setOf(SearchHitType.BOOK),
                )

            val row = root.querySelector(".search-row") as HTMLElement
            row.textContent!! shouldContain "Andy Weir"
            row.getAttribute("tabindex") shouldBe null
            row.getAttribute("role") shouldBe null
            row.querySelector(".search-chevron") shouldBe null
            row.classList.contains("is-static") shouldBe true

            row.click()

            opened shouldBe emptyList()
        }

        test("a held book's row says Held, in words a screen reader hears whole") {
            val host =
                mounts.mount {
                    SearchRow(
                        hit = bookHit("b1", "The Ministry of Time").copy(isHeld = true),
                        isOpenable = true,
                        onOpen = {},
                    )
                }

            val pill = host.querySelector(".search-row .held-pill") as HTMLElement
            pill.getAttribute("role") shouldBe "img"
            pill.getAttribute("aria-label") shouldBe "Held for review, hidden from all members"
            pill.textContent!!.trim() shouldBe "Held"
        }

        // The pill is static, but the row it sits in is pressable: an arrow over one patch of the
        // row would say that patch is dead.
        test("a held row's pill shows the row's own pointer") {
            val host =
                mounts.mount {
                    SearchRow(
                        hit = bookHit("b1", "The Ministry of Time").copy(isHeld = true),
                        isOpenable = true,
                        onOpen = {},
                    )
                }

            val row = host.querySelector(".search-row") as HTMLElement
            val pill = host.querySelector(".search-row .held-pill") as HTMLElement
            window.getComputedStyle(pill).cursor shouldBe window.getComputedStyle(row).cursor
        }

        test("an ordinary row carries no Held pill") {
            val host = mounts.mount { SearchRow(hit = bookHit("b1", "Dune"), isOpenable = true, onOpen = {}) }

            (host.querySelector(".held-pill") == null) shouldBe true
        }

        test("a held row opens like any other, into its triage page") {
            var opened = 0
            val host =
                mounts.mount {
                    SearchRow(hit = bookHit("b1", "The Ministry of Time").copy(isHeld = true), isOpenable = true, onOpen = { opened++ })
                }

            (host.querySelector(".search-row") as HTMLElement).click()

            opened shouldBe 1
        }

        // Held wins: even if both facts ever met on one hit, the inline Held pill owns the row.
        test("a restricted book's search row wears the compact lock on its cover; a held one never does") {
            val host =
                mounts.mount {
                    CompositionLocalProvider(LocalRestrictedBookIds provides setOf("b1", "b2")) {
                        SearchRow(hit = bookHit("b1", "Dune"), isOpenable = true, onOpen = {})
                        SearchRow(hit = bookHit("b2", "Ubik").copy(isHeld = true), isOpenable = true, onOpen = {})
                    }
                }
            val rows = host.querySelectorAll(".search-row").asList().map { it as HTMLElement }
            val dune = rows.single { it.textContent!!.contains("Dune") }
            val ubik = rows.single { it.textContent!!.contains("Ubik") }
            (dune.querySelector(".cover > .lu-lock.sm") != null) shouldBe true
            (ubik.querySelector(".lu-lock") == null) shouldBe true
        }
    })
