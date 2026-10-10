package com.calypsan.listenup.web.features.library

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
    })
