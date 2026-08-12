package com.calypsan.listenup.web.features.library

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
                LibraryPage(state = state, onEvent = {}, onOpenBook = {})
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

        test("an empty library that is still syncing says so, not 'empty'") {
            val root = render(loadedWith(emptyList(), syncing = true))

            root.textContent!! shouldContain "Syncing"
        }

        test("an empty library that has finished syncing says it is empty") {
            val root = render(loadedWith(emptyList(), syncing = false))

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
): LibraryUiState.Loaded =
    LibraryUiState.Loaded(
        booksSortState = ALPHA_TITLE,
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
    )

/** A minimal book — only `id` and `title` matter to this page. */
private fun bookItem(
    id: String,
    title: String,
): BookListItem =
    BookListItem(
        id = BookId(id),
        libraryId = LibraryId("lib-1"),
        folderId = FolderId("folder-1"),
        title = title,
        authors = emptyList(),
        narrators = emptyList(),
        duration = 3_600_000L,
        coverPath = null,
        addedAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
    )
