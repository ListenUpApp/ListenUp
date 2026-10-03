package com.calypsan.listenup.web.features.hardcover

import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.client.presentation.hardcover.HardcoverCandidateRow
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchedBook
import com.calypsan.listenup.client.presentation.hardcover.HardcoverSearchState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event

private val REAL =
    HardcoverCandidateRow(427_578L, 9_001L, "Project Hail Mary", listOf("Andy Weir", "Ray Porter", "Someone Else"), 2021, 8_107, true, true)
private val SUMMARY = HardcoverCandidateRow(1L, null, "Summary of Project Hail Mary", emptyList(), 2022, 0, false, false)
private val GUIDE = HardcoverCandidateRow(2L, null, "Project Hail Mary: Study Guide", listOf("Quick Reads"), null, 1, false, false)

private fun ready(
    search: HardcoverSearchState = HardcoverSearchState.Results(listOf(REAL, SUMMARY, GUIDE)),
    current: HardcoverMatchedBook? = null,
    query: String = "Project Hail Mary",
    linkingId: Long? = null,
    suggestions: List<String> = emptyList(),
    authors: String = "Andy Weir",
) = HardcoverMatchUiState.Ready("b1", "Project Hail Mary", authors, null, null, query, search, current, linkingId, false, suggestions)

private fun HTMLElement.button(label: String): HTMLButtonElement =
    querySelectorAll("button").asList().map { it as HTMLButtonElement }.first { it.textContent.orEmpty().trim() == label }

private fun HTMLElement.panel(title: String): HTMLElement? =
    querySelectorAll("section").asList().map { it as HTMLElement }.firstOrNull { it.querySelector("h2")?.textContent == title }

/** Find on Hardcover on web: grouped by author, the tells beside each result, Enter searches, and a pick is one press. */
class HardcoverMatchPageTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun mount(
            state: HardcoverMatchUiState,
            onSearch: () -> Unit = {},
            onSearchFor: (String) -> Unit = {},
            onPick: (Long) -> Unit = {},
            onRemoveMatch: () -> Unit = {},
            onQueryChange: (String) -> Unit = {},
            onOpenLibrary: () -> Unit = {},
            onOpenBook: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                HardcoverMatchPage(
                    state = state,
                    onQueryChange = onQueryChange,
                    onSearch = onSearch,
                    onSearchFor = onSearchFor,
                    onPick = onPick,
                    onRemoveMatch = onRemoveMatch,
                    onOpenLibrary = onOpenLibrary,
                    onOpenBook = onOpenBook,
                )
            }

        test("the breadcrumb runs Library, the book, Find on Hardcover — and leads back to each") {
            var library = 0
            var book = 0
            val host = mount(ready(), onOpenLibrary = { library++ }, onOpenBook = { book++ })
            awaitFrame()

            val crumb = host.querySelector(".crumb")!!
            crumb.querySelectorAll("a, .cur").asList().map { it.textContent } shouldBe
                listOf("Library", "Project Hail Mary", "Find on Hardcover")
            (crumb.querySelectorAll("a").item(0) as HTMLElement).click()
            (crumb.querySelectorAll("a").item(1) as HTMLElement).click()
            library shouldBe 1
            book shouldBe 1
            host.querySelector("h1")!!.textContent shouldBe "Find on Hardcover"
            host.textContent.orEmpty() shouldContain "Matching Project Hail Mary"
        }

        test("the book's own author's results come first and strong; the rest are quieter") {
            val host = mount(ready())
            awaitFrame()

            val byAuthor = host.panel("By Andy Weir")!!
            byAuthor.querySelectorAll(".hc-result").length shouldBe 1
            byAuthor.querySelector(".hc-results.is-quiet") shouldBe null
            val others = host.panel("Other results")!!
            others.querySelectorAll(".hc-result").length shouldBe 2
            others.querySelector(".hc-results.is-quiet") shouldBe others.querySelector(".hc-results")
        }

        test("each result says who, its format, its year and how many rated it") {
            val host = mount(ready())
            awaitFrame()

            val rows = host.querySelectorAll(".hc-result").asList().map { it.textContent.orEmpty() }
            rows[0] shouldContain "Andy Weir, Ray Porter"
            rows[0] shouldNotContain "Someone Else"
            rows[0] shouldContain "Audiobook"
            rows[0] shouldContain "2021"
            rows[0] shouldContain "8.1k ratings"
            rows[1] shouldContain "Unknown author"
            rows[1] shouldContain "No ratings yet"
            rows[1] shouldNotContain "Audiobook"
            rows[2] shouldContain "1 rating"
        }

        test("each result is one press that picks it, named for what it picks") {
            val picked = mutableListOf<Long>()
            val host = mount(ready(), onPick = { picked += it })
            awaitFrame()

            val picks = host.querySelectorAll(".hc-result button").asList().map { it as HTMLButtonElement }
            // "Pick" first, so a voice command finds it; then everything the row says (WCAG 2.5.3).
            picks.map { it.textContent.orEmpty().trim() } shouldBe
                listOf(
                    "Pick Project Hail Mary, Andy Weir, Ray Porter, Audiobook, 2021, 8.1k ratings",
                    "Pick Summary of Project Hail Mary, 2022, No ratings yet",
                    "Pick Project Hail Mary: Study Guide, Quick Reads, 1 rating",
                )
            picks.forEach { it.hasAttribute("aria-label") shouldBe false }
            picks[0].click()
            picks[1].click()
            picked shouldBe listOf(427_578L, 1L)
        }

        test("while one pick links, no second pick can start, and the one in flight says it is busy") {
            val picked = mutableListOf<Long>()
            val host = mount(ready(linkingId = 427_578L), onPick = { picked += it })
            awaitFrame()

            val picks = host.querySelectorAll(".hc-result button").asList().map { it as HTMLButtonElement }
            // Unavailable, never `disabled`: that would drop the focus of the Pick just pressed.
            picks.all { it.getAttribute("aria-disabled") == "true" && !it.disabled } shouldBe true
            picks[0].getAttribute("aria-busy") shouldBe "true"
            picks[1].click()
            picked.shouldBeEmpty()
        }

        test("Enter in the search box searches, and typing reaches the session") {
            var searches = 0
            val typed = mutableListOf<String>()
            val host = mount(ready(), onSearch = { searches++ }, onQueryChange = { typed += it })
            awaitFrame()

            val form = host.querySelector("form.hc-search") as HTMLElement
            form.getAttribute("role") shouldBe "search"
            (host.querySelector("label[for=hc-query]") as HTMLElement).textContent shouldBe "Search Hardcover"
            val input = host.querySelector("#hc-query") as HTMLInputElement
            input.value = "Hail Mary"
            input.dispatchEvent(Event("input", EventInit(bubbles = true)))
            form.dispatchEvent(Event("submit", EventInit(bubbles = true, cancelable = true)))

            typed shouldBe listOf("Hail Mary")
            searches shouldBe 1
        }

        test("nothing by the author says so, offers the search worth trying, and heads the rest Results") {
            val searched = mutableListOf<String>()
            val host =
                mount(
                    ready(
                        search = HardcoverSearchState.Results(listOf(SUMMARY, GUIDE)),
                        suggestions = listOf("Project Hail Mary Andy Weir"),
                    ),
                    onSearchFor = { searched += it },
                )
            awaitFrame()

            val weak = host.querySelector(".hc-weak")!!
            weak.getAttribute("role") shouldBe "status"
            weak.textContent.orEmpty() shouldContain "Lots of results — none by Andy Weir"
            weak.textContent.orEmpty() shouldContain "Use the full title, or add the author."
            host.button("Search “Project Hail Mary Andy Weir”").click()
            searched shouldBe listOf("Project Hail Mary Andy Weir")
            host.panel("Results")!!.querySelectorAll(".hc-result").length shouldBe 2
            host.panel("Other results") shouldBe null
        }

        test("no results says what was searched and offers each suggestion as one press") {
            val searched = mutableListOf<String>()
            val host =
                mount(
                    ready(
                        search = HardcoverSearchState.NoResults,
                        query = "Project Hail Mary (Unabridged)",
                        suggestions = listOf("Project Hail Mary", "Andy Weir"),
                    ),
                    onSearchFor = { searched += it },
                )
            awaitFrame()

            val text = host.textContent.orEmpty()
            text shouldContain "No matches on Hardcover"
            text shouldContain "Nothing found for “Project Hail Mary (Unabridged)”. Fewer words usually help — try one of these."
            host.button("Search “Andy Weir”").click()
            searched shouldBe listOf("Andy Weir")
        }

        test("a failed search is announced and offers to search again") {
            var searches = 0
            val host = mount(ready(search = HardcoverSearchState.Failed(HardcoverError.Unavailable())), onSearch = { searches++ })
            awaitFrame()

            host.querySelector("[role=alert]")!!.textContent shouldBe "Hardcover couldn't be searched just now."
            host.button("Try again").click()
            searches shouldBe 1
        }

        test("a search in flight is announced") {
            val host = mount(ready(search = HardcoverSearchState.Searching))
            awaitFrame()

            host.textContent.orEmpty() shouldContain "Searching Hardcover…"
        }

        test("the current match shows above the search, with Remove match and what removing costs") {
            var removes = 0
            val host =
                mount(
                    ready(current = HardcoverMatchedBook(427_578L, "Project Hail Mary", listOf("Andy Weir"), 2021, false, 9_001L)),
                    onRemoveMatch = { removes++ },
                )
            awaitFrame()

            val current = host.panel("Matched now")!!
            current.textContent.orEmpty() shouldContain "Andy Weir · Audiobook · 2021"
            current.textContent.orEmpty() shouldContain "ListenUp stops syncing this book until you pick one."
            host.button("Remove match").click()
            removes shouldBe 1
        }

        test("a book gone from the library says so") {
            mount(HardcoverMatchUiState.BookMissing).textContent.orEmpty() shouldContain "This book is no longer in your library."
        }

        test("a pick's toast names what it matched, with its format and year when Hardcover has them") {
            linkedToastText(REAL) shouldBe "Matched to Project Hail Mary (Audiobook, 2021)"
            linkedToastText(GUIDE) shouldBe "Matched to Project Hail Mary: Study Guide"
        }
    })
