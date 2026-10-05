package com.calypsan.listenup.web.features.serieslist

import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesProgress
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.design.LibraryFacet
import com.calypsan.listenup.web.features.library.contractBook
import com.calypsan.listenup.web.features.library.contractLibrary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * The Library's Series tab.
 *
 * What these pin: the grid keeps the ViewModel's order rather than re-sorting here, both sort
 * controls report through [LibraryUiEvent] so the shared ViewModel keeps owning persistence, the
 * Series chip is the active one, progress reads as iOS and Android word it ("Complete", "X of Y",
 * "Not started" — Simon's call, 2026-09-30), and an empty
 * tab tells "no series" apart from "still scanning".
 */
class SeriesListPageTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun seriesPage(
            state: LibraryUiState,
            onEvent: (LibraryUiEvent) -> Unit = {},
            onOpenSeries: (String) -> Unit = {},
            onSelectFacet: (LibraryFacet) -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                SeriesListPage(
                    state = state,
                    onEvent = onEvent,
                    onOpenSeries = onOpenSeries,
                    onSelectFacet = onSelectFacet,
                )
            }

        test("the grid renders the ViewModel's order rather than one of its own") {
            val root =
                seriesPage(
                    contractLibrary(
                        series =
                            listOf(
                                seriesWith("s2", "The Dark Tower", 7),
                                seriesWith("s1", "Billy Summers", 1),
                            ),
                    ),
                )

            root.querySelectorAll(".srs-name").asList().map { it.textContent } shouldContainExactly
                listOf("The Dark Tower", "Billy Summers")
        }

        test("a card counts its books, singular at one") {
            val root = seriesPage(contractLibrary(series = listOf(seriesWith("s1", "Billy Summers", 1))))

            (root.querySelector(".srs-count") as HTMLElement).textContent shouldBe "1 book"
        }

        // A parent series stands for its whole subtree, so its card says how many series it holds
        // as well as how many books — and wears a stacked edge so it reads as more than one series.
        test("a parent series' card counts its sub-series and its books, and wears a stack") {
            val parent = seriesWith("s1", "Cosmere", 23).copy(subSeriesCount = 4)
            val root = seriesPage(contractLibrary(series = listOf(parent, seriesWith("s2", "Dune", 6))))

            val counts = root.querySelectorAll(".srs-count").asList().map { it.textContent }
            counts shouldContainExactly listOf("4 series · 23 books", "6 books")
            root.querySelectorAll(".srs-cover.is-stack").length shouldBe 1
        }

        test("opening a card reports that series, not the first on the page") {
            var opened: String? = null
            val root =
                seriesPage(
                    contractLibrary(
                        series = listOf(seriesWith("s1", "Billy Summers", 1), seriesWith("s2", "The Dark Tower", 7)),
                    ),
                    onOpenSeries = { opened = it },
                )

            (root.querySelectorAll(".srs-card").item(1) as HTMLElement).click()

            opened shouldBe "s2"
        }

        test("a part-read series says how far through it the reader is") {
            val root =
                seriesPage(
                    contractLibrary(
                        series = listOf(seriesWith("s1", "The Dark Tower", 7)),
                        seriesProgress = mapOf(SeriesId("s1") to SeriesProgress(finishedCount = 3, totalCount = 7)),
                    ),
                )

            (root.querySelector(".srs-progress") as HTMLElement).textContent shouldBe "3 of 7"
        }

        test("a finished series says so rather than counting to itself") {
            val root =
                seriesPage(
                    contractLibrary(
                        series = listOf(seriesWith("s1", "The Dark Tower", 7)),
                        seriesProgress = mapOf(SeriesId("s1") to SeriesProgress(finishedCount = 7, totalCount = 7)),
                    ),
                )

            (root.querySelector(".srs-progress") as HTMLElement).textContent shouldBe "Complete"
        }

        test("an unstarted series says it is not started, quietly") {
            val root =
                seriesPage(
                    contractLibrary(
                        series = listOf(seriesWith("s1", "The Dark Tower", 7)),
                        seriesProgress = mapOf(SeriesId("s1") to SeriesProgress(finishedCount = 0, totalCount = 7)),
                    ),
                )

            val line = root.querySelector(".srs-progress") as HTMLElement
            line.textContent shouldBe "Not started"
            line.classList.contains("srs-progress-idle") shouldBe true
        }

        // The same fallback as iOS's `progressFor`: no aggregate for a series means nothing finished.
        test("a series the aggregate has no entry for reads as not started") {
            val root =
                seriesPage(
                    contractLibrary(
                        series = listOf(seriesWith("s1", "The Dark Tower", 7)),
                        seriesProgress = emptyMap(),
                    ),
                )

            (root.querySelector(".srs-progress") as HTMLElement).textContent shouldBe "Not started"
        }

        test("the sort row marks the active category and reports a change through the shared event") {
            val events = mutableListOf<LibraryUiEvent>()
            val root =
                seriesPage(
                    contractLibrary(
                        series = listOf(seriesWith("s1", "Billy Summers", 1)),
                        seriesSortState = SortState(SortCategory.BOOK_COUNT, SortDirection.ASCENDING),
                    ),
                    onEvent = { events += it },
                )

            (root.querySelector(".lib-sort-option.is-active") as HTMLElement).textContent shouldBe
                SortCategory.BOOK_COUNT.label
            (root.querySelectorAll(".lib-sort-option").item(0) as HTMLElement).click()

            events shouldContainExactly listOf(LibraryUiEvent.SeriesCategoryChanged(SortCategory.NAME))
        }

        test("the direction toggle reports the series toggle, not the books one") {
            val events = mutableListOf<LibraryUiEvent>()
            val root =
                seriesPage(
                    contractLibrary(series = listOf(seriesWith("s1", "Billy Summers", 1))),
                    onEvent = { events += it },
                )

            (root.querySelector(".lib-sort-direction") as HTMLElement).click()

            events shouldContainExactly listOf(LibraryUiEvent.SeriesDirectionToggled)
        }

        test("the Series chip is the active one, so the reader knows which tab they are on") {
            val root = seriesPage(contractLibrary())

            (root.querySelector(".facet-chip.is-active") as HTMLElement).textContent shouldBe "Series"
        }

        // ⛔ Zero series mid-scan is not an empty library, and saying the wrong one is worse than
        // saying nothing — the same distinction the Books tab draws.
        test("an empty tab tells a still-scanning library apart from one with no series") {
            val scanning = seriesPage(contractLibrary(isBuildingInitialLibrary = true))
            (scanning.querySelector(".empty h2") as HTMLElement).textContent shouldBe "Still reading your library"

            val settled = seriesPage(contractLibrary(isBuildingInitialLibrary = false))
            (settled.querySelector(".empty h2") as HTMLElement).textContent shouldBe "No series yet"
        }

        test("the header and facets survive a state with no data to show") {
            val root = seriesPage(LibraryUiState.Loading)

            (root.querySelector(".loading") as HTMLElement).textContent shouldBe "Loading…"
            root.querySelector(".facet-row") shouldNotBe null
            (root.querySelector(".facet-chip.is-active") as HTMLElement).textContent shouldBe "Series"
            // Sorting stays with the loaded branch: offering to reorder nothing does nothing.
            root.querySelector(".lib-sort") shouldBe null
        }
    })

private fun seriesWith(
    id: String,
    name: String,
    bookCount: Int,
): SeriesWithBooks {
    val books = (1..bookCount).map { contractBook("$id-b$it", "$name $it") }
    return SeriesWithBooks(
        series = Series(id = SeriesId(id), name = name),
        books = books,
        bookSequences = books.associate { it.id.value to null },
    )
}
