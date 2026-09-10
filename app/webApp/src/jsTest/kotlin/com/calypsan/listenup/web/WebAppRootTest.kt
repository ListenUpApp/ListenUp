package com.calypsan.listenup.web

import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.web.features.bookedit.fixedBookEdit
import com.calypsan.listenup.web.features.bookdetail.fixedBookDetail
import com.calypsan.listenup.web.features.bookdetail.readyBook
import com.calypsan.listenup.web.features.contributordetail.ContributorDetailSession
import com.calypsan.listenup.web.features.contributordetail.OpenContributorDetail
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorEvent
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.metadata.MetadataEvent
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterSetProblem
import com.calypsan.listenup.web.features.chaptereditor.chapter
import com.calypsan.listenup.client.presentation.books.BookMultiSelectEvent
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditEvent
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.web.features.bulkedit.editing
import com.calypsan.listenup.web.features.bulkedit.fixedBulkEdit
import com.calypsan.listenup.client.presentation.books.SelectionMode
import com.calypsan.listenup.web.features.books.fixedMultiSelect
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.web.features.library.contractBook
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditNavAction
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataEvent
import com.calypsan.listenup.web.features.contributormetadata.contributorSearchState
import com.calypsan.listenup.web.features.contributormetadata.fixedContributorMetadata
import com.calypsan.listenup.web.features.contributormetadata.localContributor
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditNavAction
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.web.features.contributordetail.fixedContributorDetail
import com.calypsan.listenup.web.features.contributordetail.readyContributor
import com.calypsan.listenup.web.features.contributordetail.seriesWithBooks
import com.calypsan.listenup.web.features.seriesdetail.fixedSeriesDetail
import com.calypsan.listenup.web.features.seriesdetail.readySeries
import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.client.presentation.notifications.NotificationsUiState
import com.calypsan.listenup.web.features.notifications.fixedNotificationBell
import com.calypsan.listenup.web.features.notifications.fixedNotifications
import com.calypsan.listenup.web.features.notifications.notification
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsUiState
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsEvent
import com.calypsan.listenup.web.features.admin.fixedLibrarySettings
import com.calypsan.listenup.web.features.admin.readyLibrary
import com.calypsan.listenup.web.features.notifications.fixedNotificationPrefs
import com.calypsan.listenup.web.features.notifications.pref
import com.calypsan.listenup.client.presentation.settings.SettingsUiState
import com.calypsan.listenup.web.features.settings.fixedSettings
import com.calypsan.listenup.client.presentation.profile.UserProfileUiState
import com.calypsan.listenup.web.features.profile.fixedProfile
import com.calypsan.listenup.web.features.profile.readyProfile
import com.calypsan.listenup.web.features.profile.ProfileSession
import com.calypsan.listenup.web.features.contributors.ContributorsSession
import com.calypsan.listenup.web.features.contributors.OpenContributors
import com.calypsan.listenup.web.features.contributors.contributor
import com.calypsan.listenup.web.features.contributors.fixedContributors
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.features.search.bookHit
import com.calypsan.listenup.web.features.search.contributorHit
import com.calypsan.listenup.web.features.search.searchResult
import com.calypsan.listenup.web.nav.Router
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
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
import com.calypsan.listenup.web.features.search.seriesHit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.EventInit
import org.w3c.dom.events.Event
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.web.features.home.OpenHome
import com.calypsan.listenup.web.features.home.fixedHome
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

        fun navLabels(host: HTMLElement): List<String> {
            val items = host.querySelectorAll(".nav-i")
            return (0 until items.length).map { (items.item(it) as HTMLElement).textContent.orEmpty() }
        }

        /** The sidebar entry labeled [label] — the one that carries `on` when its page is showing. */
        fun navItem(
            host: HTMLElement,
            label: String,
        ): HTMLElement {
            val items = host.querySelectorAll(".nav-i")
            return (0 until items.length)
                .map { items.item(it) as HTMLElement }
                .first { it.textContent == label }
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
            val (host, router, composition) = mountAt("/")

            try {
                val labels = navLabels(host)
                labels.none { it.contains("Admin") } shouldBe true
                labels.any { it.contains("Settings") } shouldBe true
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("an admin gets the Admin entry") {
            val (host, router, composition) = mountAt("/", isAdmin = flowOf(true))

            try {
                // collectAsState starts false; the flow flips it on the next recomposition.
                withTimeout(RECOMPOSE_TIMEOUT_MS) {
                    while (navLabels(host).none { it.contains("Admin") }) delay(10)
                }
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("the active sidebar item derives from the URL") {
            val (host, router, composition) = mountAt("/library")

            try {
                val activeItem = host.querySelector(".nav-i.on") as HTMLElement
                activeItem.textContent.orEmpty() shouldContain "Library"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("the root URL is Home") {
            val (host, router, composition) = mountAt("/")

            try {
                val activeItem = host.querySelector(".nav-i.on") as HTMLElement
                activeItem.textContent.orEmpty() shouldContain "Home"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("clicking a sidebar item rewrites the URL") {
            val (host, router, composition) = mountAt("/")

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
                composition.dispose()
                router.dispose()
            }
        }

        test("/library/contributors renders the contributors page with Authors active") {
            val (host, router, composition) = mountAt("/library/contributors")

            try {
                host.querySelectorAll(".contrib-header").length shouldBe 1
                facetChip(host, "Authors").classList.contains("is-active") shouldBe true
                facetChip(host, "Narrators").classList.contains("is-active") shouldBe false
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("?role=narrator renders the contributors page with Narrators active") {
            val (host, router, composition) = mountAt("/library/contributors?role=narrator")

            try {
                facetChip(host, "Narrators").classList.contains("is-active") shouldBe true
                facetChip(host, "Authors").classList.contains("is-active") shouldBe false
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("a junk role falls back to Author, not Narrator, not no chip at all") {
            val (host, router, composition) = mountAt("/library/contributors?role=banana")

            try {
                facetChip(host, "Authors").classList.contains("is-active") shouldBe true
                facetChip(host, "Narrators").classList.contains("is-active") shouldBe false
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("selecting a facet navigates to the route it stands for") {
            // The facet row is part of the Loaded library render — a Loading library shows the
            // "Loading…" placeholder and no chips at all — so this needs a library that has
            // actually answered, unlike the routing-only specs above.
            val (host, router, composition) = mountAt("/library", openLibrary = fakeLibrary(contractLibrary()))

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
                composition.dispose()
                router.dispose()
            }
        }

        test("switching facet role opens a new session rather than reusing the old one's") {
            // A1 only proved the toggle gesture escapes the page; this closes the gap the plan
            // flagged — that nothing yet proved `openContributors(role)` is re-invoked when the
            // role actually changes, which is exactly what a bare `remember { }` would get wrong.
            val recorder = RecordingContributors()
            val (host, router, composition) = mountAt("/library/contributors", openContributors = recorder.open)

            try {
                recorder.requestedRoles shouldBe listOf(ContributorRole.AUTHOR)

                facetChip(host, "Narrators").click()
                awaitFrame()

                recorder.requestedRoles shouldBe listOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR)
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("neither an 'In progress' nor a 'Series' chip exists in the facet row") {
            val (host, router, composition) = mountAt("/library", openLibrary = fakeLibrary(contractLibrary()))

            try {
                val chips = host.querySelectorAll(".facet-chip")
                val labels = (0 until chips.length).map { (chips.item(it) as HTMLElement).textContent }
                labels shouldBe listOf("Books", "Authors", "Narrators")
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("/book/{id}/chapters renders the chapter editor, not the book's page") {
            val recorder = RecordingChapterEditor()
            val (host, router) = mountAt("/book/b-stormlight/chapters", openChapterEditor = recorder.open)

            try {
                recorder.requestedIds shouldBe listOf("b-stormlight")
                (host.querySelector(".ched-t") as HTMLElement).textContent shouldBe "Edit chapters"
                // ⛔ The book's own page must not also be up. `/book/{id}` is a prefix of this
                // route, and a branch order that tests it first makes the editor unreachable.
                host.querySelector(".bd-title") shouldBe null
            } finally {
                router.dispose()
            }
        }

        test("Edit chapters on a book's chapters pane opens the editor") {
            val (host, router) =
                mountAt("/book/b-stormlight?tab=chapters", openBookDetail = fixedBookDetail(readyBook()))

            try {
                (host.querySelector(".bd-chapters-edit button") as HTMLElement).click()
                awaitFrame()

                window.location.pathname shouldBe "/book/b-stormlight/chapters"
            } finally {
                router.dispose()
            }
        }

        test("a saved chapter set lands back on the book it belongs to") {
            val recorder = RecordingChapterEditor(flowOf(ChapterEditorEvent.Saved))
            val (_, router) = mountAt("/book/b-stormlight/chapters", openChapterEditor = recorder.open)

            try {
                awaitFrame()

                window.location.pathname shouldBe "/book/b-stormlight"
            } finally {
                router.dispose()
            }
        }

        // ⛔ The editor holds the only copy of the reader's unsaved work, so Back is a question
        // rather than a navigation while the draft is dirty.
        test("leaving a dirty chapter draft asks before it is thrown away") {
            val recorder = RecordingChapterEditor(chapters = listOf(chapter("c1", "One", 0L, 1_000L)))
            val (host, router) = mountAt("/book/b-stormlight/chapters", openChapterEditor = recorder.open)

            try {
                awaitFrame()
                host
                    .querySelectorAll("button")
                    .asList()
                    .filterIsInstance<HTMLButtonElement>()
                    .first { it.textContent?.trim() == "Back" }
                    .click()
                awaitFrame()

                host
                    .querySelector("dialog")
                    .shouldNotBeNull()
                    .textContent
                    .shouldNotBeNull() shouldContain
                    "The changes you made here will be lost."
                window.location.pathname shouldBe "/book/b-stormlight/chapters"
                recorder.resets shouldBe emptyList()

                host
                    .querySelectorAll("dialog button")
                    .asList()
                    .filterIsInstance<HTMLButtonElement>()
                    .first { it.textContent?.trim() == "Discard" }
                    .click()
                awaitFrame()

                recorder.resets.size shouldBe 1
                window.location.pathname shouldBe "/book/b-stormlight"
            } finally {
                router.dispose()
            }
        }

        // ⛔ A refused save must SAY which row is wrong. The ViewModel reports the problems and
        // nothing else on the page would: `SaveFailed` goes to the error bus, but `Invalid` means
        // nothing ever left the device.
        test("a chapter set refused before it is sent names the row responsible") {
            val recorder =
                RecordingChapterEditor(
                    events = flowOf(ChapterEditorEvent.Invalid(listOf(ChapterSetProblem.BlankTitle("c2")))),
                    chapters = listOf(chapter("c1", "One", 0L, 1_000L), chapter("c2", "", 1_000L, 1_000L)),
                )
            val (host, router) = mountAt("/book/b-stormlight/chapters", openChapterEditor = recorder.open)

            try {
                awaitFrame()

                host.querySelector(".ched-problem")?.textContent shouldBe "Chapter 2 needs a title."
                window.location.pathname shouldBe "/book/b-stormlight/chapters"
            } finally {
                router.dispose()
            }
        }

        // ⛔ The seed comes from the BOOK, and the book has to have loaded first. A session opened
        // before Book Detail's state arrives seeds an empty query, and the reader lands on a search
        // that finds nothing on a book the wizard could have found immediately.
        test("/book/{id}/match seeds the search from the book it was opened on") {
            val recorder = RecordingMetadata()
            val (host, router) =
                mountAt(
                    "/book/b-kings/match",
                    openBookDetail = fixedBookDetail(readyBook()),
                    openMetadata = recorder.open,
                )

            try {
                awaitFrame()

                recorder.seeds.size shouldBe 1
                recorder.seeds.single() shouldContain "b-kings|"
                (host.querySelector(".mdx-t") as HTMLElement).textContent shouldBe "Match metadata"
                // ⛔ The book's own page must not also be up.
                host.querySelector(".bd-t") shouldBe null
            } finally {
                router.dispose()
            }
        }

        test("the wizard waits for the book rather than seeding an empty search") {
            val recorder = RecordingMetadata()
            val (_, router) =
                mountAt(
                    "/book/b-kings/match",
                    openBookDetail = fixedBookDetail(BookDetailUiState.Loading),
                    openMetadata = recorder.open,
                )

            try {
                awaitFrame()

                recorder.seeds shouldBe emptyList()
            } finally {
                router.dispose()
            }
        }

        test("Match metadata on a book's page opens the wizard") {
            val (host, router) = mountAt("/book/b-kings", openBookDetail = fixedBookDetail(readyBook()))

            try {
                (host.querySelector("button[aria-label=\"Match metadata\"]") as HTMLElement).click()
                awaitFrame()

                window.location.pathname shouldBe "/book/b-kings/match"
            } finally {
                router.dispose()
            }
        }

        test("an applied match lands back on the book it changed") {
            val recorder = RecordingMetadata(events = flowOf(MetadataEvent.MatchApplied))
            val (_, router) =
                mountAt(
                    "/book/b-kings/match",
                    openBookDetail = fixedBookDetail(readyBook()),
                    openMetadata = recorder.open,
                )

            try {
                awaitFrame()

                window.location.pathname shouldBe "/book/b-kings"
            } finally {
                router.dispose()
            }
        }

        // MARK: multi-select over the library

        test("the bulk bar appears once selection is on, and counts what is picked") {
            val (host, router) =
                mountAt(
                    "/library",
                    openLibrary = fakeLibrary(contractLibrary(books = listOf(contractBook("b1", "Kings")))),
                    openMultiSelect = fixedMultiSelect(selectionMode = SelectionMode.Active(setOf("b1", "b2"))),
                )

            try {
                host.querySelector(".bulk")?.textContent.shouldNotBeNull() shouldContain "2 selected"
            } finally {
                router.dispose()
            }
        }

        test("no bar while selection is off") {
            val (host, router) =
                mountAt(
                    "/library",
                    openLibrary = fakeLibrary(contractLibrary(books = listOf(contractBook("b1", "Kings")))),
                    openMultiSelect = fixedMultiSelect(selectionMode = SelectionMode.None),
                )

            try {
                host.querySelector(".bulk").shouldBeNull()
            } finally {
                router.dispose()
            }
        }

        // ⛔ Nothing selected means no actions, not disabled ones. The bar is already saying
        // "0 selected"; a row of greyed-out verbs under that says nothing the count has not.
        test("an empty selection offers a count and a way out, and no verbs") {
            val (host, router) =
                mountAt(
                    "/library",
                    openLibrary = fakeLibrary(contractLibrary(books = listOf(contractBook("b1", "Kings")))),
                    openMultiSelect = fixedMultiSelect(selectionMode = SelectionMode.Active(emptySet())),
                )

            try {
                host.querySelector(".bulk").shouldNotBeNull()
                host.querySelectorAll(".bulk-b").asList().size shouldBe 0
            } finally {
                router.dispose()
            }
        }

        // Collections are admin-managed, the same rule Book Detail's own collection picker follows.
        test("only an admin is offered a collection") {
            val books = listOf(contractBook("b1", "Kings"))
            val member =
                mountAt(
                    "/library",
                    openLibrary = fakeLibrary(contractLibrary(books = books)),
                    openMultiSelect =
                        fixedMultiSelect(selectionMode = SelectionMode.Active(setOf("b1")), isAdmin = false),
                )
            try {
                member.first
                    .querySelectorAll(".bulk-b")
                    .asList()
                    .map { it.textContent } shouldBe
                    listOf("Edit", "Add to shelf")
            } finally {
                member.second.dispose()
            }

            val admin =
                mountAt(
                    "/library",
                    openLibrary = fakeLibrary(contractLibrary(books = books)),
                    openMultiSelect =
                        fixedMultiSelect(selectionMode = SelectionMode.Active(setOf("b1")), isAdmin = true),
                )
            try {
                admin.first
                    .querySelectorAll(".bulk-b")
                    .asList()
                    .map { it.textContent } shouldBe
                    listOf("Edit", "Add to shelf", "Add to collection")
            } finally {
                admin.second.dispose()
            }
        }

        test("the shelf picker lists the reader's shelves and reports the one chosen") {
            val picked = mutableListOf<String>()
            val (host, router) =
                mountAt(
                    "/library",
                    openLibrary = fakeLibrary(contractLibrary(books = listOf(contractBook("b1", "Kings")))),
                    openMultiSelect =
                        fixedMultiSelect(
                            selectionMode = SelectionMode.Active(setOf("b1", "b2")),
                            myShelves = listOf(testShelf("s1", "Winter reading")),
                            onAddToShelf = { picked += it },
                        ),
                )

            try {
                host
                    .querySelectorAll(".bulk-b")
                    .asList()
                    .filterIsInstance<HTMLButtonElement>()
                    .first { it.textContent == "Add to shelf" }
                    .click()
                awaitFrame()

                host.querySelector("dialog")?.textContent.shouldNotBeNull() shouldContain "2 books"
                val target = host.querySelector(".sel-target") as HTMLButtonElement
                target.textContent.shouldNotBeNull() shouldContain "Winter reading"
                target.click()
                awaitFrame()

                picked shouldBe listOf("s1")
            } finally {
                router.dispose()
            }
        }

        // ⛔ A picker with no create path strands the reader whose first bulk action is exactly the
        // reason they want a new shelf.
        test("the shelf picker can make a new shelf and add to it in one step") {
            val created = mutableListOf<String>()
            val (host, router) =
                mountAt(
                    "/library",
                    openLibrary = fakeLibrary(contractLibrary(books = listOf(contractBook("b1", "Kings")))),
                    openMultiSelect =
                        fixedMultiSelect(
                            selectionMode = SelectionMode.Active(setOf("b1")),
                            onCreateShelfAndAdd = { created += it },
                        ),
                )

            try {
                host
                    .querySelectorAll(".bulk-b")
                    .asList()
                    .filterIsInstance<HTMLButtonElement>()
                    .first { it.textContent == "Add to shelf" }
                    .click()
                awaitFrame()

                host.querySelector(".sel-none")?.textContent shouldBe "You have no shelves yet."

                val field = host.querySelector("#sel-new-name") as HTMLInputElement
                field.value = "Winter reading"
                field.dispatchEvent(Event("input", EventInit(bubbles = true)))
                awaitFrame()

                host
                    .querySelectorAll("dialog button")
                    .asList()
                    .filterIsInstance<HTMLButtonElement>()
                    .first { it.textContent == "Create and add" }
                    .click()
                awaitFrame()

                created shouldBe listOf("Winter reading")
            } finally {
                router.dispose()
            }
        }

        // ⛔ A blank name cannot create anything. Sabotage proved the earlier spec never exercised
        // this: it typed a name before pressing, so a Create that accepted "" passed it.
        test("creating is refused until the new shelf has a name") {
            val (host, router) =
                mountAt(
                    "/library",
                    openLibrary = fakeLibrary(contractLibrary(books = listOf(contractBook("b1", "Kings")))),
                    openMultiSelect = fixedMultiSelect(selectionMode = SelectionMode.Active(setOf("b1"))),
                )

            try {
                host
                    .querySelectorAll(".bulk-b")
                    .asList()
                    .filterIsInstance<HTMLButtonElement>()
                    .first { it.textContent == "Add to shelf" }
                    .click()
                awaitFrame()

                val create =
                    host
                        .querySelectorAll("dialog button")
                        .asList()
                        .filterIsInstance<HTMLButtonElement>()
                        .first { it.textContent == "Create and add" }
                create.hasAttribute("disabled") shouldBe true

                val field = host.querySelector("#sel-new-name") as HTMLInputElement
                field.value = "   "
                field.dispatchEvent(Event("input", EventInit(bubbles = true)))
                awaitFrame()

                host
                    .querySelectorAll("dialog button")
                    .asList()
                    .filterIsInstance<HTMLButtonElement>()
                    .first { it.textContent == "Create and add" }
                    .hasAttribute("disabled") shouldBe true
            } finally {
                router.dispose()
            }
        }

        test("a completed bulk action is confirmed with the number the reader cannot see") {
            val said = mutableListOf<String>()
            val (_, router) =
                mountAt(
                    "/library",
                    openLibrary = fakeLibrary(contractLibrary(books = listOf(contractBook("b1", "Kings")))),
                    openMultiSelect =
                        fixedMultiSelect(
                            selectionMode = SelectionMode.Active(setOf("b1")),
                            events = flowOf(BookMultiSelectEvent.BooksAddedToShelf(count = 12)),
                        ),
                    onToast = { said += it },
                )

            try {
                awaitFrame()

                said shouldBe listOf("Added 12 books to the shelf.")
            } finally {
                router.dispose()
            }
        }

        // MARK: the bulk editor

        test("Edit on the bulk bar carries the selection into the editor's URL") {
            val (host, router) =
                mountAt(
                    "/library",
                    openLibrary = fakeLibrary(contractLibrary(books = listOf(contractBook("b1", "Kings")))),
                    openMultiSelect = fixedMultiSelect(selectionMode = SelectionMode.Active(setOf("b1", "b2"))),
                )

            try {
                host
                    .querySelectorAll(".bulk-b")
                    .asList()
                    .filterIsInstance<HTMLButtonElement>()
                    .first { it.textContent == "Edit" }
                    .click()
                awaitFrame()

                window.location.pathname shouldBe "/books/edit"
                // ⛔ The ids ride the query, and all of them do: an editor opened over a subset of
                // what was picked would write to fewer books than the reader chose.
                window.location.search shouldContain "ids=b1,b2"
            } finally {
                router.dispose()
            }
        }

        test("/books/edit?ids=… opens the editor over exactly those books") {
            val (host, router) =
                mountAt(
                    "/books/edit?ids=b1,b2,b3",
                    openBulkEdit = fixedBulkEdit(editing(bookCount = 3)),
                )

            try {
                (host.querySelector(".bke-t") as HTMLElement).textContent shouldBe "Edit 3 books"
            } finally {
                router.dispose()
            }
        }

        // ⛔ No ids is not an empty editor — it is not this route at all. A bulk editor over nothing
        // would offer a Change button with no books behind it.
        //
        // ⛔ Asserts on `.bke`, the editor's own container, NOT on `.bke-t`. The default session is
        // Loading, which draws a skeleton and no title — so a `.bke-t` assertion passed even when
        // the route DID match, which sabotage proved.
        test("/books/edit with no ids is not the editor") {
            val (host, router) = mountAt("/books/edit")

            try {
                host.querySelector(".bke") shouldBe null
            } finally {
                router.dispose()
            }
        }

        test("a finished bulk edit says what it changed and lands back on the library") {
            val said = mutableListOf<String>()
            val (_, router) =
                mountAt(
                    "/books/edit?ids=b1,b2",
                    openBulkEdit =
                        fixedBulkEdit(
                            state = editing(bookCount = 2),
                            events = flowOf(BulkEditEvent.Applied(changedCount = 2)),
                        ),
                    onToast = { said += it },
                )

            try {
                awaitFrame()

                said shouldBe listOf("2 books updated")
                window.location.pathname shouldBe "/library"
            } finally {
                router.dispose()
            }
        }

        // ⛔ There is no rollback, so a failure that does not name how many books were already
        // committed leaves the reader unable to tell what state their library is in.
        test("a bulk edit that stopped partway says how far it got") {
            val said = mutableListOf<String>()
            val (_, router) =
                mountAt(
                    "/books/edit?ids=b1,b2",
                    openBulkEdit =
                        fixedBulkEdit(
                            state = editing(bookCount = 2),
                            events =
                                flowOf(
                                    BulkEditEvent.Failed(
                                        error = InternalError(debugInfo = "nope"),
                                        appliedCount = 7,
                                    ),
                                ),
                        ),
                    onToast = { said += it },
                )

            try {
                awaitFrame()

                said.single() shouldContain "Stopped after 7 books"
                window.location.pathname shouldBe "/library"
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

        test("/contributor/{id}/match renders the wizard, not the contributor's page") {
            val (host, router) =
                mountAt(
                    "/contributor/c-king/match",
                    openContributorMetadata =
                        fixedContributorMetadata(contributorSearchState(current = localContributor(name = "Pat"))),
                )

            try {
                (host.querySelector(".cmx-t") as HTMLElement).textContent shouldBe "Match contributor"
                // ⛔ `/contributor/{id}` is a prefix of this route; a branch order that tests it
                // first makes the wizard unreachable by link.
                host.querySelector(".cd-name") shouldBe null
            } finally {
                router.dispose()
            }
        }

        test("the sparkle on a contributor's page opens the wizard") {
            val (host, router) =
                mountAt("/contributor/c-king", openContributorDetail = fixedContributorDetail(readyContributor()))

            try {
                (host.querySelector(".cd-match") as HTMLElement).click()
                awaitFrame()

                window.location.pathname shouldBe "/contributor/c-king/match"
            } finally {
                router.dispose()
            }
        }

        test("an applied contributor profile lands back on the person it changed") {
            val (_, router) =
                mountAt(
                    "/contributor/c-king/match",
                    openContributorMetadata =
                        fixedContributorMetadata(
                            state = contributorSearchState(),
                            events = flowOf(ContributorMetadataEvent.MetadataApplied),
                        ),
                )

            try {
                awaitFrame()

                window.location.pathname shouldBe "/contributor/c-king"
            } finally {
                router.dispose()
            }
        }

        test("/contributor/{id}/edit renders the form over that contributor, not their page") {
            val recorder = RecordingContributorEdit()
            val (host, router) = mountAt("/contributor/c-king/edit", openContributorEdit = recorder.open)

            try {
                recorder.requestedIds shouldBe listOf("c-king")
                (host.querySelector(".ced-title") as HTMLElement).textContent shouldBe "Person c-king"
                // ⛔ The detail page must not also be up. `/contributor/{id}` is a prefix of this
                // route, and a branch order that tests it first makes the form unreachable by link.
                host.querySelector(".cd-name") shouldBe null
            } finally {
                router.dispose()
            }
        }

        test("the pencil on a contributor's page opens the form over them") {
            val (host, router) =
                mountAt("/contributor/c-king", openContributorDetail = fixedContributorDetail(readyContributor()))

            try {
                (host.querySelector(".cd-edit") as HTMLElement).click()
                awaitFrame()

                window.location.pathname shouldBe "/contributor/c-king/edit"
            } finally {
                router.dispose()
            }
        }

        test("leaving the form lands back on the contributor it was editing") {
            val recorder = RecordingContributorEdit(flowOf(ContributorEditNavAction.NavigateBack))
            val (_, router) = mountAt("/contributor/c-king/edit", openContributorEdit = recorder.open)

            try {
                awaitFrame()

                window.location.pathname shouldBe "/contributor/c-king"
            } finally {
                router.dispose()
            }
        }

        // ⛔ `replace`, not `navigate`. A merge can delete the contributor being edited — the
        // rename-collision path folds them into someone else — so a pushed entry would send Back
        // to the editor of a contributor that no longer exists.
        test("a merge lands on the survivor, and Back does not return to the deleted one") {
            // ⛔ One-shot, not `flowOf`. A cold flow re-emits to the session that a Back would
            // remount, which redirects forward again and hides the pushed entry — sabotage proved
            // `navigate` in place of `replace` survived this spec until the merge fired once.
            val merged = Channel<ContributorEditNavAction>(Channel.BUFFERED)
            merged.trySend(ContributorEditNavAction.NavigateToMerged(ContributorId("c-bachman")))
            val recorder = RecordingContributorEdit(merged.receiveAsFlow())
            // Two pushes, so what lies behind the editor is known: `mountAt` replaces the entry it
            // lands on, and every spec here shares one window, so without this Back returns to
            // whatever an earlier spec happened to push. `history.length` cannot stand in for it —
            // the browser caps the stack, so a push stops growing it long before this spec runs.
            window.history.pushState(null, "", MERGE_SENTINEL_PATH)
            window.history.pushState(null, "", MERGE_SENTINEL_PATH)
            val (_, router) = mountAt("/contributor/c-king/edit", openContributorEdit = recorder.open)

            try {
                awaitFrame()

                window.location.pathname shouldBe "/contributor/c-bachman"

                window.history.back()
                // history.back() is asynchronous; a frame is not enough to see popstate land.
                delay(POPSTATE_SETTLE_MS)

                window.location.pathname shouldBe MERGE_SENTINEL_PATH
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

        test("/series/{id} renders the detail page for that id") {
            val recorder = RecordingSeriesDetail()
            val (host, router) = mountAt("/series/s-cosmere", openSeriesDetail = recorder.open)

            try {
                recorder.requestedIds shouldBe listOf("s-cosmere")
                (host.querySelector(".sd-t") as HTMLElement).textContent shouldBe "Series s-cosmere"
            } finally {
                router.dispose()
            }
        }

        test("/series/{id}/edit renders the form over that series, not its page") {
            val recorder = RecordingSeriesEdit()
            val (host, router) = mountAt("/series/s-cosmere/edit", openSeriesEdit = recorder.open)

            try {
                recorder.requestedIds shouldBe listOf("s-cosmere")
                (host.querySelector(".sed-title") as HTMLElement).textContent shouldBe "Series s-cosmere"
                // ⛔ The detail page must not also be up. `/series/{id}` is a prefix of this route,
                // and a branch order that tests it first makes the form unreachable by link.
                host.querySelector(".sd-t") shouldBe null
            } finally {
                router.dispose()
            }
        }

        test("the pencil on a series page opens the form over it") {
            val (host, router) =
                mountAt("/series/s-cosmere", openSeriesDetail = fixedSeriesDetail(readySeries()))

            try {
                (host.querySelector(".sd-edit") as HTMLElement).click()
                awaitFrame()

                window.location.pathname shouldBe "/series/s-cosmere/edit"
            } finally {
                router.dispose()
            }
        }

        test("leaving the series form lands back on the series it was editing") {
            val recorder = RecordingSeriesEdit(flowOf(SeriesEditNavAction.NavigateBack))
            val (_, router) = mountAt("/series/s-cosmere/edit", openSeriesEdit = recorder.open)

            try {
                awaitFrame()

                window.location.pathname shouldBe "/series/s-cosmere"
            } finally {
                router.dispose()
            }
        }

        // ⛔ `replace`, not `navigate`. A series merge deletes the series being edited, so a pushed
        // entry would send Back to the editor of a series that no longer exists. See the
        // contributor spec above for why the action fires once and a known entry is pushed first.
        test("a series merge lands on the survivor, and Back does not return to the deleted one") {
            val merged = Channel<SeriesEditNavAction>(Channel.BUFFERED)
            merged.trySend(SeriesEditNavAction.NavigateToMerged(SeriesId("s-mistborn")))
            val recorder = RecordingSeriesEdit(merged.receiveAsFlow())
            window.history.pushState(null, "", MERGE_SENTINEL_PATH)
            window.history.pushState(null, "", MERGE_SENTINEL_PATH)
            val (_, router) = mountAt("/series/s-cosmere/edit", openSeriesEdit = recorder.open)

            try {
                awaitFrame()

                window.location.pathname shouldBe "/series/s-mistborn"

                window.history.back()
                // history.back() is asynchronous; a frame is not enough to see popstate land.
                delay(POPSTATE_SETTLE_MS)

                window.location.pathname shouldBe MERGE_SENTINEL_PATH
            } finally {
                router.dispose()
            }
        }

        // A series is reached FROM the library and belongs to it. Leaving no sidebar entry lit
        // reads as having navigated out of the app entirely.
        test("a series page keeps Library lit in the sidebar") {
            val (host, router) = mountAt("/series/s-cosmere", openSeriesDetail = fixedSeriesDetail(readySeries()))

            try {
                navItem(host, "Library").classList.contains("on") shouldBe true
            } finally {
                router.dispose()
            }
        }

        test("switching series id opens a new session rather than reusing the old one's") {
            val recorder = RecordingSeriesDetail()
            val (host, router) = mountAt("/series/s1", openSeriesDetail = recorder.open)

            try {
                recorder.requestedIds shouldBe listOf("s1")

                router.navigate(Route(listOf("series", "s2")))
                awaitFrame()

                recorder.requestedIds shouldBe listOf("s1", "s2")
            } finally {
                router.dispose()
            }
        }

        test("a series chip on a book opens that series") {
            val (host, router) =
                mountAt(
                    "/book/42",
                    openBookDetail =
                        fixedBookDetail(
                            readyBook(
                                series = listOf(BookSeries(seriesId = "s-cosmere", seriesName = "The Cosmere", sequence = 7.0)),
                            ),
                        ),
                )

            try {
                (host.querySelector(".bd-series-chip") as HTMLElement).click()

                window.location.pathname shouldBe "/series/s-cosmere"
            } finally {
                router.dispose()
            }
        }

        test("a series card on a contributor's page opens that series") {
            val (host, router) =
                mountAt(
                    "/contributor/c-king",
                    openContributorDetail =
                        fixedContributorDetail(readyContributor(series = listOf(seriesWithBooks(id = "s-dt")))),
                )

            try {
                (host.querySelector(".cd-series-card") as HTMLElement).click()

                window.location.pathname shouldBe "/series/s-dt"
            } finally {
                router.dispose()
            }
        }

        test("/notifications renders the inbox") {
            val (host, router) =
                mountAt(
                    "/notifications",
                    openNotifications = fixedNotifications(NotificationsUiState.Data(listOf(notification()))),
                )

            try {
                (host.querySelector(".ntf-t") as HTMLElement).textContent shouldBe "Registration waiting"
            } finally {
                router.dispose()
            }
        }

        // The badge is the only thing on the shell that says there is anything to look at.
        test("the sidebar bell carries the unread count") {
            val (host, router) = mountAt("/", openNotificationBell = fixedNotificationBell(unreadCount = 3))

            try {
                (host.querySelector(".nav-badge") as HTMLElement).textContent shouldBe "3"
            } finally {
                router.dispose()
            }
        }

        // A zero badge is a red dot claiming something is waiting when nothing is.
        test("a read inbox shows no badge at all") {
            val (host, router) = mountAt("/", openNotificationBell = fixedNotificationBell(unreadCount = 0))

            try {
                host.querySelector(".nav-badge") shouldBe null
            } finally {
                router.dispose()
            }
        }

        test("a three-digit count stops being a number and says so") {
            val (host, router) = mountAt("/", openNotificationBell = fixedNotificationBell(unreadCount = 128))

            try {
                (host.querySelector(".nav-badge") as HTMLElement).textContent shouldBe "99+"
            } finally {
                router.dispose()
            }
        }

        test("opening a notification marks it read") {
            val marked = mutableListOf<String>()
            val (host, router) =
                mountAt(
                    "/notifications",
                    openNotifications =
                        fixedNotifications(
                            NotificationsUiState.Data(listOf(notification(id = "n9"))),
                            onMarkRead = { marked += it },
                        ),
                )

            try {
                (host.querySelector(".ntf-row") as HTMLElement).click()

                marked shouldBe listOf("n9")
            } finally {
                router.dispose()
            }
        }

        // The destination comes from the shared tap mapping, so the browser cannot disagree with
        // the phone about where a notification goes. Web's Admin page IS the approvals surface.
        test("a pending-registration notification lands on Admin") {
            val (host, router) =
                mountAt(
                    "/notifications",
                    isAdmin = flowOf(true),
                    openNotifications =
                        fixedNotifications(
                            NotificationsUiState.Data(
                                listOf(notification(event = NotificationEvent.RegistrationApproval(userId = "u1"))),
                            ),
                        ),
                )

            try {
                (host.querySelector(".ntf-row") as HTMLElement).click()

                window.location.pathname shouldBe "/admin"
            } finally {
                router.dispose()
            }
        }

        // A notification whose destination has no web surface still acknowledges the press.
        test("a notification with nowhere to go marks read and stays put") {
            val marked = mutableListOf<String>()
            val (host, router) =
                mountAt(
                    "/notifications",
                    openNotifications =
                        fixedNotifications(
                            NotificationsUiState.Data(
                                listOf(
                                    notification(
                                        id = "n3",
                                        event = NotificationEvent.RegistrationDecision(userId = "u1", approved = true),
                                    ),
                                ),
                            ),
                            onMarkRead = { marked += it },
                        ),
                )

            try {
                (host.querySelector(".ntf-row") as HTMLElement).click()

                marked shouldBe listOf("n3")
                window.location.pathname shouldBe "/notifications"
            } finally {
                router.dispose()
            }
        }

        test("/search renders the search page") {
            val (host, router, composition) = mountAt("/search")

            try {
                host.querySelector(".search-page") shouldNotBe null
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("/search?q=dune seeds the query into the field from the URL") {
            val (host, router, composition) = mountAt("/search?q=dune", openSearch = reactiveSearch())

            try {
                withTimeout(RECOMPOSE_TIMEOUT_MS) {
                    while ((host.querySelector(".f-input") as HTMLInputElement).value != "dune") delay(10)
                }
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("typing in the search field updates the URL without stacking a history entry") {
            val (host, router, composition) = mountAt("/search", openSearch = reactiveSearch())

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
                composition.dispose()
                router.dispose()
            }
        }

        test("clicking a book hit navigates to /book/{id}") {
            val result =
                searchResult(
                    query = "dune",
                    hits = listOf(bookHit("b1", "Dune"), contributorHit("c1", "Frank Herbert")),
                )
            val (host, router, composition) = mountAt("/search", openSearch = hitNavigatingSearch(result))

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
                composition.dispose()
                router.dispose()
            }
        }

        test("clicking a contributor hit navigates to that person's page") {
            // The counterpart to the non-openable spec below: /contributor/{id} exists now, so a
            // person found in search must actually be reachable from it.
            val result =
                searchResult(
                    query = "herbert",
                    hits = listOf(bookHit("b1", "Dune"), contributorHit("c9", "Frank Herbert")),
                )
            val (host, router) = mountAt("/search", openSearch = hitNavigatingSearch(result))

            try {
                val row =
                    host.querySelectorAll(".search-row").let { rows ->
                        (0 until rows.length)
                            .map { rows.item(it) as HTMLElement }
                            .first { it.textContent.orEmpty().contains("Frank Herbert") }
                    }
                row.click()
                withTimeout(RECOMPOSE_TIMEOUT_MS) {
                    while (window.location.pathname != "/contributor/c9") delay(10)
                }
            } finally {
                router.dispose()
            }
        }

        test("a hit type with no destination is not clickable and never navigates") {
            // SERIES has no route at all — its row must carry no button semantics, and
            // clicking it must leave the reader exactly where they were. (CONTRIBUTOR used to
            // sit here; it became openable the moment /contributor/{id} landed.)
            val result = searchResult(query = "dune", hits = listOf(seriesHit("s1", "Dune")))
            val (host, router, composition) = mountAt("/search", openSearch = hitNavigatingSearch(result))

            try {
                val row = host.querySelector(".search-row") as HTMLElement
                row.getAttribute("role") shouldBe null

                row.click()
                awaitFrame()

                window.location.pathname shouldBe "/search"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("the sidebar's Search item lands on the real search page, not the placeholder") {
            val (host, router, composition) = mountAt("/")

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
                composition.dispose()
                router.dispose()
            }
        }
    })

/** How long `history.back()` takes to become a popstate the router has actually seen. */
private const val POPSTATE_SETTLE_MS = 120L

/** The entry a merge redirect must leave behind it — anything else means it pushed one. */
private const val MERGE_SENTINEL_PATH = "/before-the-editor"

/** A shelf with only the fields the picker reads. */
private fun testShelf(
    id: String,
    name: String,
) = Shelf(
    id = ShelfId(id),
    name = name,
    description = null,
    isPrivate = false,
    ownerId = "u1",
    ownerDisplayName = "Simon",
    bookCount = 0,
    totalDurationSeconds = 0L,
    createdAtMs = 0L,
    updatedAtMs = 0L,
)
