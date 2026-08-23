package com.calypsan.listenup.web.features.library

import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement

/**
 * Rendering contracts for the Library page.
 *
 * The empty-vs-syncing distinction is the one worth testing: both render zero books, and telling a
 * reader "your library is empty" while it is still seeding is a lie the UI would tell confidently.
 */
class LibraryPageTest :
    FunSpec({

        fun render(state: LibraryUiState): HTMLElement {
            val root = document.createElement("div") as HTMLElement
            document.body?.appendChild(root)
            renderComposable(root = root) {
                LibraryPage(state = state, onEvent = {}, onOpenBook = {}, onSelectFacet = {})
            }
            return root
        }

        test("a loaded library renders one card per book") {
            val root = render(loadedWith(listOf(bookItem("b1", "Dune"), bookItem("b2", "Ubik"))))

            root.querySelectorAll(".lib-card").length shouldBe 2
            root.textContent!! shouldContain "Dune"
            root.textContent!! shouldContain "Ubik"
        }

        test("each card points its cover at the server blob endpoint") {
            val root = render(loadedWith(listOf(bookItem("b1", "Dune"))))

            val img = root.querySelector(".lib-cover") as HTMLImageElement
            img.getAttribute("src")!! shouldContain "/api/v1/books/b1/cover"
        }

        // The grid paints ~190px tiles from covers the server stores at 2400px. Pixel count is what
        // costs a browser, not file size, so asking for the rung that fits is the whole point of the
        // arc: 36x fewer pixels to decode per tile.
        test("a cover is requested at the ladder rung the tile needs, not full size") {
            val root = render(loadedWith(listOf(bookItem("b1", "Dune"))))

            val img = root.querySelector(".lib-cover") as HTMLImageElement
            img.getAttribute("src")!! shouldContain "w=300"
        }

        // Density is the browser's decision, not ours: srcset lets a 2x display take the 600px rung
        // and a 1x display take the 300px one, from markup that states both.
        test("a cover offers the larger rung to denser displays") {
            val root = render(loadedWith(listOf(bookItem("b1", "Dune"))))

            val srcset = (root.querySelector(".lib-cover") as HTMLImageElement).getAttribute("srcset")!!
            srcset shouldContain "w=300 1x"
            srcset shouldContain "w=600 2x"
        }

        // \u26d4 The server serves covers `immutable` for a year, so the URL is the ONLY thing that can
        // tell a browser the artwork changed. Without this a re-covered book stays stale on web
        // until the cache expires — a live bug on main that Android and desktop never had.
        test("a cover URL carries the artwork hash, so re-covering a book is visible") {
            val root = render(loadedWith(listOf(bookItem("b1", "Dune", coverHash = "abc123"))))

            val img = root.querySelector(".lib-cover") as HTMLImageElement
            img.getAttribute("src")!! shouldContain "v=abc123"
            img.getAttribute("srcset")!! shouldContain "v=abc123"
        }

        test("a cover with no known hash simply omits the version") {
            val root = render(loadedWith(listOf(bookItem("b1", "Dune"))))

            val img = root.querySelector(".lib-cover") as HTMLImageElement
            img.getAttribute("src")!! shouldNotContain "v="
        }

        // A real library is ~1200 books, and every card carries a cover. Eager loading fetches and
        // decodes all of them at once — measured at 1195 books: 1195 images in the DOM for 40 on
        // screen, and scrolling suffered for it. `lazy` is what keeps the grid's cost proportional
        // to what the reader is actually looking at.
        test("covers load lazily, so a large library does not fetch every image at once") {
            val root = render(loadedWith(listOf(bookItem("b1", "Dune"))))

            val img = root.querySelector(".lib-cover") as HTMLImageElement
            img.getAttribute("loading") shouldBe "lazy"
        }

        test("a title-sorted library is split by first letter, as Android is") {
            val root =
                render(
                    loadedWith(listOf(bookItem("b1", "Abaddon's Gate"), bookItem("b2", "Dune"), bookItem("b3", "Ubik"))),
                )

            root.querySelectorAll(".lib-section").length shouldBe 3
            root.querySelector(".lib-section")!!.textContent shouldBe "A"
        }

        test("consecutive books under one letter share a single header") {
            val root =
                render(loadedWith(listOf(bookItem("b1", "Dune"), bookItem("b2", "Dust"), bookItem("b3", "Ubik"))))

            root.querySelectorAll(".lib-section").length shouldBe 2
        }

        // Headers key off the sort. "Added" and "Duration" are not alphabetical, so a letter rail
        // over them would label runs of books with letters that mean nothing.
        test("a date-sorted library has no letter headers") {
            val root =
                render(
                    loadedWith(
                        listOf(bookItem("b1", "Dune"), bookItem("b2", "Ubik")),
                        sort = SortCategory.ADDED,
                    ),
                )

            root.querySelectorAll(".lib-section").length shouldBe 0
        }

        test("a card shows its author under the title, as the other platforms do") {
            val root = render(loadedWith(listOf(bookItem("b1", "Dune", authors = listOf("Frank Herbert")))))

            root.querySelector(".lib-author")!!.textContent!! shouldBe "Frank Herbert"
        }

        // Changed deliberately when the grid was virtualised: the author line now always occupies
        // its row, empty or not, because the windowing arithmetic requires every card to be exactly
        // the same height — a card that dropped a line would be shorter than its neighbours and the
        // row offsets would drift. It renders no text, so nothing is visible either way; what is
        // pinned here is that the element is present and blank rather than absent.
        test("a card with no author still reserves the author line, and leaves it empty") {
            val root = render(loadedWith(listOf(bookItem("b1", "Dune"))))

            root.querySelectorAll(".lib-author").length shouldBe 1
            root.querySelector(".lib-author")!!.textContent!! shouldBe ""
        }

        test("an empty library that is still being built says so, not 'empty'") {
            val root = render(loadedWith(emptyList(), building = true))

            root.textContent!! shouldContain "Syncing"
        }

        // The regression this arc actually hit. Against a real 1195-book library the socket reports
        // Connected — so `isSyncing` is FALSE — for the whole of the initial seed, and the screen
        // confidently read "No books yet" while books were streaming in. Connection state is not
        // population state, and only `isBuildingInitialLibrary` knows the difference.
        test("a connected client mid-seed does not claim the library is empty") {
            val root = render(loadedWith(emptyList(), syncing = false, building = true))

            root.textContent!! shouldContain "Syncing"
        }

        test("an empty library that has finished building says it is empty") {
            val root = render(loadedWith(emptyList(), syncing = false, building = false))

            root.textContent!! shouldContain "No books"
        }

        test("loading renders the placeholder, not an empty library") {
            val root = render(LibraryUiState.Loading)

            root.querySelectorAll(".lib-card").length shouldBe 0
            root.textContent!! shouldContain "Loading"
        }
    })

private val ALPHA_TITLE = SortState(SortCategory.TITLE, SortDirection.ASCENDING)

/**
 * A `Loaded` state with real values for every field the page does not read.
 *
 * `Loaded` carries four tabs' worth of content and sort state; spelling all of it out in each test
 * would bury the one field that test is about.
 */
private fun loadedWith(
    books: List<BookListItem>,
    syncing: Boolean = false,
    building: Boolean = false,
    sort: SortCategory = SortCategory.TITLE,
): LibraryUiState.Loaded =
    LibraryUiState.Loaded(
        booksSortState = SortState(sort, SortDirection.ASCENDING),
        seriesSortState = ALPHA_TITLE,
        authorsSortState = ALPHA_TITLE,
        narratorsSortState = ALPHA_TITLE,
        ignoreTitleArticles = false,
        hideSingleBookSeries = false,
        books = books,
        series = emptyList(),
        authors = emptyList(),
        narrators = emptyList(),
        bookProgress = emptyMap(),
        bookIsFinished = emptyMap(),
        booksInProgress = emptyList(),
        seriesProgress = emptyMap(),
        syncState = if (syncing) SyncState.Syncing else SyncState.Idle,
        isServerScanning = false,
        scanProgress = null,
        isBuildingInitialLibrary = building,
    )

/** A minimal book — only `id`, `title` and `authors` reach this page. */
private fun bookItem(
    id: String,
    title: String,
    authors: List<String> = emptyList(),
    coverHash: String? = null,
): BookListItem =
    BookListItem(
        id = BookId(id),
        libraryId = LibraryId("lib-1"),
        folderId = FolderId("folder-1"),
        title = title,
        authors = authors.mapIndexed { i, name -> BookContributor(id = "c$i", name = name) },
        narrators = emptyList(),
        duration = 3_600_000L,
        coverPath = null,
        coverHash = coverHash,
        addedAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
    )
