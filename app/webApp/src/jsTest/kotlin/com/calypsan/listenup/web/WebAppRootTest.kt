package com.calypsan.listenup.web

import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.web.features.bookedit.fixedBookEdit
import com.calypsan.listenup.web.features.bookdetail.fixedBookDetail
import com.calypsan.listenup.web.features.bookdetail.readyBook
import com.calypsan.listenup.web.features.contributordetail.ContributorDetailSession
import com.calypsan.listenup.web.features.contributordetail.OpenContributorDetail
import com.calypsan.listenup.web.features.contributordetail.fixedContributorDetail
import com.calypsan.listenup.web.features.contributordetail.readyContributor
import com.calypsan.listenup.web.features.contributors.ContributorsSession
import com.calypsan.listenup.web.features.contributors.OpenContributors
import com.calypsan.listenup.web.features.contributors.contributor
import com.calypsan.listenup.web.features.contributors.fixedContributors
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.Router
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLElement
import org.jetbrains.compose.web.renderComposable
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.calypsan.listenup.web.features.library.OpenLibrary
import com.calypsan.listenup.web.features.library.contractLibrary
import com.calypsan.listenup.web.features.library.fakeLibrary
import com.calypsan.listenup.web.features.nowplaying.fixedPlayback
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult
import com.calypsan.listenup.client.presentation.search.SearchNavAction
import com.calypsan.listenup.client.presentation.search.SearchUiState
import com.calypsan.listenup.web.features.search.bookHit
import com.calypsan.listenup.web.features.search.contributorHit
import com.calypsan.listenup.web.features.search.searchResult
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.EventInit
import org.w3c.dom.events.Event
import com.calypsan.listenup.web.features.search.OpenSearch
import com.calypsan.listenup.web.features.search.SearchSession
import com.calypsan.listenup.web.features.search.fixedSearch

/**
 * The root wiring: the sidebar drives the URL and the URL drives the sidebar. This is where the
 * "URL is the contract" rule becomes observable behaviour rather than a codec property.
 */
class WebAppRootTest :
    FunSpec({

        var originalUrl = ""

        beforeTest {
            originalUrl = window.location.pathname + window.location.search
        }

        afterTest {
            window.history.replaceState(null, "", originalUrl)
        }

        fun mountAt(
            path: String,
            isAdmin: Flow<Boolean> = flowOf(false),
            openContributorDetail: OpenContributorDetail = fixedContributorDetail(ContributorDetailUiState.Loading),
            openContributors: OpenContributors = fixedContributors(emptyList()),
            openLibrary: OpenLibrary = fakeLibrary(),
            openSearch: OpenSearch = fixedSearch(SearchUiState.Idle()),
        ): Pair<HTMLElement, Router> {
            window.history.replaceState(null, "", path)
            val router = Router()
            val host = document.createElement("div") as HTMLElement
            document.body!!.appendChild(host)
            renderComposable(root = host) {
                WebAppRoot(
                    router,
                    fixedBookDetail(readyBook()),
                    fixedBookEdit(BookEditUiState()),
                    openContributorDetail,
                    openContributors,
                    openLibrary,
                    openSearch,
                    fixedPlayback(),
                    observeIsAdmin = { isAdmin },
                )
            }
            return host to router
        }

        fun navLabels(host: HTMLElement): List<String> {
            val items = host.querySelectorAll(".nav-i")
            return (0 until items.length).map { (items.item(it) as HTMLElement).textContent.orEmpty() }
        }

        /** The rendered facet chip labeled [label], wherever it sits in the current page. */
        fun facetChip(
            host: HTMLElement,
            label: String,
        ): HTMLElement {
            val chips = host.querySelectorAll(".facet-chip")
            return (0 until chips.length)
                .map { chips.item(it) as HTMLElement }
                .first { it.textContent == label }
        }

        test("the Admin entry waits for proof of admin") {
            // The entry used to be hardcoded for everyone — a member saw an Admin item whose
            // every destination would refuse them. The sidebar renders it only once the
            // repository says so.
            val (host, router) = mountAt("/")

            try {
                val labels = navLabels(host)
                labels.none { it.contains("Admin") } shouldBe true
                labels.any { it.contains("Settings") } shouldBe true
            } finally {
                router.dispose()
            }
        }

        test("an admin gets the Admin entry") {
            val (host, router) = mountAt("/", isAdmin = flowOf(true))

            try {
                // collectAsState starts false; the flow flips it on the next recomposition.
                withTimeout(RECOMPOSE_TIMEOUT_MS) {
                    while (navLabels(host).none { it.contains("Admin") }) delay(10)
                }
            } finally {
                router.dispose()
            }
        }

        test("the active sidebar item derives from the URL") {
            val (host, router) = mountAt("/library")

            try {
                val activeItem = host.querySelector(".nav-i.on") as HTMLElement
                activeItem.textContent.orEmpty() shouldContain "Library"
            } finally {
                router.dispose()
            }
        }

        test("the root URL is Home") {
            val (host, router) = mountAt("/")

            try {
                val activeItem = host.querySelector(".nav-i.on") as HTMLElement
                activeItem.textContent.orEmpty() shouldContain "Home"
            } finally {
                router.dispose()
            }
        }

        test("clicking a sidebar item rewrites the URL") {
            val (host, router) = mountAt("/")

            try {
                val items = host.querySelectorAll(".nav-i")
                (items.item(1) as HTMLElement).click()

                window.location.pathname shouldBe "/library"
                // Recomposition is frame-scheduled, so the re-rendered active item only exists
                // after the next frame.
                awaitFrame()
                (host.querySelector(".nav-i.on") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "Library"
            } finally {
                router.dispose()
            }
        }

        test("/library/contributors renders the contributors page with Authors active") {
            val (host, router) = mountAt("/library/contributors")

            try {
                host.querySelectorAll(".contrib-header").length shouldBe 1
                facetChip(host, "Authors").classList.contains("is-active") shouldBe true
                facetChip(host, "Narrators").classList.contains("is-active") shouldBe false
            } finally {
                router.dispose()
            }
        }

        test("?role=narrator renders the contributors page with Narrators active") {
            val (host, router) = mountAt("/library/contributors?role=narrator")

            try {
                facetChip(host, "Narrators").classList.contains("is-active") shouldBe true
                facetChip(host, "Authors").classList.contains("is-active") shouldBe false
            } finally {
                router.dispose()
            }
        }

        test("a junk role falls back to Author, not Narrator, not no chip at all") {
            val (host, router) = mountAt("/library/contributors?role=banana")

            try {
                facetChip(host, "Authors").classList.contains("is-active") shouldBe true
                facetChip(host, "Narrators").classList.contains("is-active") shouldBe false
            } finally {
                router.dispose()
            }
        }

        test("selecting a facet navigates to the route it stands for") {
            // The facet row is part of the Loaded library render — a Loading library shows the
            // "Loading…" placeholder and no chips at all — so this needs a library that has
            // actually answered, unlike the routing-only specs above.
            val (host, router) = mountAt("/library", openLibrary = fakeLibrary(contractLibrary()))

            try {
                facetChip(host, "Authors").click()
                window.location.pathname shouldBe "/library/contributors"
                window.location.search shouldBe ""

                facetChip(host, "Narrators").click()
                window.location.pathname shouldBe "/library/contributors"
                window.location.search shouldBe "?role=narrator"

                facetChip(host, "Books").click()
                window.location.pathname shouldBe "/library"
                window.location.search shouldBe ""
            } finally {
                router.dispose()
            }
        }

        test("switching facet role opens a new session rather than reusing the old one's") {
            // A1 only proved the toggle gesture escapes the page; this closes the gap the plan
            // flagged — that nothing yet proved `openContributors(role)` is re-invoked when the
            // role actually changes, which is exactly what a bare `remember { }` would get wrong.
            val recorder = RecordingContributors()
            val (host, router) = mountAt("/library/contributors", openContributors = recorder.open)

            try {
                recorder.requestedRoles shouldBe listOf(ContributorRole.AUTHOR)

                facetChip(host, "Narrators").click()
                awaitFrame()

                recorder.requestedRoles shouldBe listOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR)
            } finally {
                router.dispose()
            }
        }

        test("neither an 'In progress' nor a 'Series' chip exists in the facet row") {
            val (host, router) = mountAt("/library", openLibrary = fakeLibrary(contractLibrary()))

            try {
                val chips = host.querySelectorAll(".facet-chip")
                val labels = (0 until chips.length).map { (chips.item(it) as HTMLElement).textContent }
                labels shouldBe listOf("Books", "Authors", "Narrators")
            } finally {
                router.dispose()
            }
        }

        test("/contributor/{id} renders the detail page for that id") {
            val recorder = RecordingContributorDetail()
            val (host, router) = mountAt("/contributor/c-king", openContributorDetail = recorder.open)

            try {
                recorder.requestedIds shouldBe listOf("c-king")
                (host.querySelector(".cd-name") as HTMLElement).textContent shouldBe "Contributor c-king"
            } finally {
                router.dispose()
            }
        }

        test("selecting a contributor row on the list navigates to that contributor's page") {
            val (host, router) =
                mountAt(
                    "/library/contributors",
                    openContributors = fixedContributors(listOf(contributor("c1", "Andy Weir", 3))),
                )

            try {
                (host.querySelector(".contrib-row") as HTMLElement).click()

                window.location.pathname shouldBe "/contributor/c1"
            } finally {
                router.dispose()
            }
        }

        test("switching contributor id opens a new session rather than reusing the old one's") {
            // The list-row test above only proves the gesture navigates; this closes the gap that
            // mattered for Task A2's facet-role session — a bare `remember { }` here would show
            // the first person's page forever, no matter which id the URL named next.
            val recorder = RecordingContributorDetail()
            val (host, router) = mountAt("/contributor/c1", openContributorDetail = recorder.open)

            try {
                recorder.requestedIds shouldBe listOf("c1")

                router.navigate(Route(listOf("contributor", "c2")))
                awaitFrame()

                recorder.requestedIds shouldBe listOf("c1", "c2")
            } finally {
                router.dispose()
            }
        }

        test("/search renders the search page") {
            val (host, router) = mountAt("/search")

            try {
                host.querySelector(".search-page") shouldNotBe null
            } finally {
                router.dispose()
            }
        }

        test("/search?q=dune seeds the query into the field from the URL") {
            val (host, router) = mountAt("/search?q=dune", openSearch = reactiveSearch())

            try {
                withTimeout(RECOMPOSE_TIMEOUT_MS) {
                    while ((host.querySelector(".f-input") as HTMLInputElement).value != "dune") delay(10)
                }
            } finally {
                router.dispose()
            }
        }

        test("typing in the search field updates the URL without stacking a history entry") {
            val (host, router) = mountAt("/search", openSearch = reactiveSearch())

            try {
                val lengthBeforeTyping = window.history.length
                val input = host.querySelector(".f-input") as HTMLInputElement

                // One keystroke at a time, the way a reader actually types — if any of these
                // pushed rather than replaced, history.length would grow by one per call.
                listOf("d", "du", "dun", "dune").forEach { partial ->
                    input.value = partial
                    input.dispatchEvent(Event("input", EventInit(bubbles = true)))
                }

                withTimeout(RECOMPOSE_TIMEOUT_MS) {
                    while (window.location.search != "?q=dune") delay(10)
                }
                window.history.length shouldBe lengthBeforeTyping
            } finally {
                router.dispose()
            }
        }

        test("clicking a book hit navigates to /book/{id}") {
            val result =
                searchResult(
                    query = "dune",
                    hits = listOf(bookHit("b1", "Dune"), contributorHit("c1", "Frank Herbert")),
                )
            val (host, router) = mountAt("/search", openSearch = hitNavigatingSearch(result))

            try {
                val bookRow =
                    host.querySelectorAll(".search-row").let { rows ->
                        (0 until rows.length)
                            .map { rows.item(it) as HTMLElement }
                            .first { it.textContent.orEmpty().contains("Dune") }
                    }
                bookRow.click()

                // The nav action rides a Channel — the router.navigate() call happens on the
                // next resumption of the collecting coroutine, not synchronously with the click.
                withTimeout(RECOMPOSE_TIMEOUT_MS) {
                    while (window.location.pathname == "/search") delay(10)
                }
                window.location.pathname shouldBe "/book/b1"
            } finally {
                router.dispose()
            }
        }

        test("a hit type with no destination is not clickable and never navigates") {
            // CONTRIBUTOR has no route on this branch yet — its row must carry no button
            // semantics, and clicking it must leave the reader exactly where they were.
            val result = searchResult(query = "weir", hits = listOf(contributorHit("c1", "Andy Weir")))
            val (host, router) = mountAt("/search", openSearch = hitNavigatingSearch(result))

            try {
                val row = host.querySelector(".search-row") as HTMLElement
                row.getAttribute("role") shouldBe null

                row.click()
                awaitFrame()

                window.location.pathname shouldBe "/search"
            } finally {
                router.dispose()
            }
        }

        test("the sidebar's Search item lands on the real search page, not the placeholder") {
            val (host, router) = mountAt("/")

            try {
                val items = host.querySelectorAll(".nav-i")
                val searchItem =
                    (0 until items.length).map { items.item(it) as HTMLElement }.first { it.textContent == "Search" }
                searchItem.click()

                window.location.pathname shouldBe "/search"
                awaitFrame()
                host.querySelector(".search-page") shouldNotBe null
                host.textContent.orEmpty() shouldNotContain "This page is not built yet."
            } finally {
                router.dispose()
            }
        }
    })

/** An [OpenContributors] that records every role it was asked to open, in the order asked. */
private class RecordingContributors {
    val requestedRoles = mutableListOf<ContributorRole>()
    val open: OpenContributors = { role ->
        requestedRoles += role
        ContributorsSession(state = MutableStateFlow(emptyList()), close = {})
    }
}

/** An [OpenContributorDetail] that records every id it was asked to open, in the order asked. */
private class RecordingContributorDetail {
    val requestedIds = mutableListOf<String>()
    val open: OpenContributorDetail = { id ->
        requestedIds += id
        ContributorDetailSession(
            state = MutableStateFlow(readyContributor(name = "Contributor $id")),
            close = {},
        )
    }
}

/** Resolves after the next animation frame — when a scheduled recomposition has applied. */
private suspend fun awaitFrame() {
    suspendCoroutine { continuation ->
        window.requestAnimationFrame { window.requestAnimationFrame { continuation.resume(Unit) } }
    }
}

/** How long a spec waits for a state-flow value to reach the DOM. */
private const val RECOMPOSE_TIMEOUT_MS = 2_000L

/**
 * A Search session whose state genuinely reacts to [onQueryChanged] — synchronously and without
 * the real ViewModel's debounce or FTS call, so a spec about the `?q=` URL seam does not need a
 * database behind it. Always [SearchUiState.Idle]; these specs assert only on `.query`, never on
 * which phase renders — that contract already belongs to `SearchPageTest`.
 */
private fun reactiveSearch(): OpenSearch {
    val state = MutableStateFlow<SearchUiState>(SearchUiState.Idle())
    return {
        SearchSession(
            state = state,
            onQueryChanged = { query -> state.value = SearchUiState.Idle(query = query) },
            onToggleType = {},
            onOpenHit = {},
            retry = {},
            navActions = emptyFlow(),
            close = {},
        )
    }
}

/**
 * A Search session whose [SearchUiState.Results] is fixed but whose hit clicks genuinely emit
 * [SearchNavAction]s — enough to prove [com.calypsan.listenup.web.WebAppRoot] routes them without a
 * real ViewModel or FTS index behind it.
 */
private fun hitNavigatingSearch(result: SearchResult): OpenSearch {
    val navChannel = Channel<SearchNavAction>(Channel.BUFFERED)
    return {
        SearchSession(
            state =
                MutableStateFlow(
                    SearchUiState.Results(query = result.query, selectedTypes = emptySet(), result = result),
                ),
            onQueryChanged = {},
            onToggleType = {},
            onOpenHit = { hit -> navChannel.trySend(navActionFor(hit)) },
            retry = {},
            navActions = navChannel.receiveAsFlow(),
            close = {},
        )
    }
}

/** Mirrors [com.calypsan.listenup.client.presentation.search.SearchViewModel.onResultSelected]'s
 *  own id+type mapping — this fixture only needs to prove the mapping reaches the router. */
private fun navActionFor(hit: SearchHit): SearchNavAction =
    when (hit.type) {
        SearchHitType.BOOK -> SearchNavAction.NavigateToBook(hit.id)
        SearchHitType.CONTRIBUTOR -> SearchNavAction.NavigateToContributor(hit.id)
        SearchHitType.SERIES -> SearchNavAction.NavigateToSeries(hit.id)
        SearchHitType.TAG -> SearchNavAction.NavigateToTag(hit.id, hit.name)
    }
