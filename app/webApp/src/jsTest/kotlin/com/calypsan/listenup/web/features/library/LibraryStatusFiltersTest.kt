package com.calypsan.listenup.web.features.library

import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.presentation.library.BookCardStatus
import com.calypsan.listenup.client.presentation.library.BookStatusCounts
import com.calypsan.listenup.client.presentation.library.BookStatusFilter
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

class LibraryStatusFiltersTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        val counts = BookStatusCounts(all = 1286, inProgress = 4, notStarted = 1019, finished = 263)

        test("the chips carry whole-library counts, grouped, and the active one is pressed") {
            val root =
                mounts.mount { StatusFilterRow(selected = BookStatusFilter.FINISHED, counts = counts, onSelect = {}) }
            val chips = root.querySelectorAll(".lib-filters .pill").asList().map { it as HTMLElement }
            chips.map { it.textContent } shouldBe listOf("All · 1,286", "In progress · 4", "Not started · 1,019", "Finished · 263")
            chips.map { it.getAttribute("aria-pressed") } shouldBe listOf("false", "false", "false", "true")
            root.querySelector(".lib-filters")!!.getAttribute("aria-label") shouldBe "Show"
        }

        test("choosing a chip reports its filter") {
            var chosen: BookStatusFilter? = null
            val root = mounts.mount { StatusFilterRow(selected = BookStatusFilter.ALL, counts = counts, onSelect = { chosen = it }) }
            (root.querySelectorAll(".lib-filters .pill").item(1) as HTMLElement).click()
            chosen shouldBe BookStatusFilter.IN_PROGRESS
        }

        test("the count line names the books and the days of listening") {
            libraryCountLine(all = 1286, totalDurationMs = 41L * 86_400_000L + 3_600_000L) shouldBe "1,286 books · 41 days of listening"
            libraryCountLine(all = 1, totalDurationMs = 86_400_000L) shouldBe "1 book · 1 day of listening"
            libraryCountLine(all = 3, totalDurationMs = 5L * 3_600_000L) shouldBe "3 books · 5 hours of listening"
        }

        test("the last filter is replayed into the next session, so Book Detail and back keeps it") {
            val sent = mutableListOf<LibraryUiEvent>()
            val open =
                rememberingStatusFilter {
                    LibrarySession(state = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading), onEvent = { sent += it }, close = {})
                }
            open().onEvent(LibraryUiEvent.StatusFilterChanged(BookStatusFilter.FINISHED))
            sent.clear()

            open()

            sent shouldBe listOf(LibraryUiEvent.StatusFilterChanged(BookStatusFilter.FINISHED))
        }

        test("a first session sends nothing: All is the ViewModel's own default") {
            val sent = mutableListOf<LibraryUiEvent>()
            rememberingStatusFilter { LibrarySession(MutableStateFlow(LibraryUiState.Loading), { sent += it }, {}) }()
            sent shouldBe emptyList()
        }

        test("the card's last line says time left, Finished and length, or the length") {
            cardLastLine(BookCardStatus.InProgress(fraction = 0.1f, timeLeftMs = 145_860_000L), durationMs = 0L) shouldBe "40h 31m left"
            cardLastLine(BookCardStatus.Finished(durationMs = 43_440_000L), durationMs = 0L) shouldBe "Finished · 12h 4m"
            cardLastLine(BookCardStatus.NotStarted(durationMs = 43_440_000L), durationMs = 0L) shouldBe "12h 4m"
            cardLastLine(null, durationMs = 43_440_000L) shouldBe "12h 4m"
        }

        test("a started card shows a progress rail on the art and its time left; a finished one a badge") {
            val book = contractBook("b1", "The Way of Kings")
            val started = mounts.mount {
                BookCard(book = book, status = BookCardStatus.InProgress(0.5f, 1_800_000L), onOpen = {})
            }
            started.querySelector(".lib-cover [role=progressbar]")!!.getAttribute("aria-valuenow") shouldBe "50"
            started.querySelector(".lib-meta")!!.textContent shouldBe "30m left"
            started.querySelector(".lib-meta")!!.classList.contains("is-progress") shouldBe true

            val finished = mounts.mount { BookCard(book = book, status = BookCardStatus.Finished(3_600_000L), onOpen = {}) }
            finished.querySelector(".lib-cover .lib-done")!!.getAttribute("aria-label") shouldBe "Finished"
            finished.querySelector(".lib-cover [role=progressbar]") shouldBe null
        }

        test("the card's tooltip carries the narrator, so the dense grid can drop the line") {
            val book = contractBook("b1", "Dune").copy(
                authors = listOf(BookContributor("a1", "Frank Herbert")),
                narrators = listOf(BookContributor("n1", "Scott Brick")),
                duration = 75_720_000L,
            )
            val root = mounts.mount { BookCard(book = book, status = BookCardStatus.NotStarted(75_720_000L), onOpen = {}) }
            root.querySelector(".lib-card")!!.getAttribute("title") shouldBe "Dune · Frank Herbert · read by Scott Brick · 21h 2m"
            root.querySelector(".lib-narrator")!!.textContent shouldBe "Read by Scott Brick"
        }
    })
