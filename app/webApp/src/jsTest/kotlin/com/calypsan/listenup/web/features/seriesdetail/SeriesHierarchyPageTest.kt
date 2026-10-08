package com.calypsan.listenup.web.features.seriesdetail

import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesEvent
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesUiState
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * A series that lives inside another, and a series that holds others, on the series page.
 *
 * What these pin: a child page's breadcrumb is the series path and every step opens its series; a
 * parent's hero counts its sub-series; Continue names the book on a parent page and stays "Continue"
 * on a flat one; sub-series are cards with their progress and a stack hint; the Books panel groups
 * its rows under headings at the right level, each heading a link, a folded group offering "Show
 * all N"; and the Add sub-series tile exists only for an editor, disabled offline.
 */
class SeriesHierarchyPageTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        val opened = mutableListOf<String>()
        val toggled = mutableListOf<String>()
        val adderEvents = mutableListOf<AddSubSeriesEvent>()
        beforeTest {
            opened.clear()
            toggled.clear()
            adderEvents.clear()
        }

        fun page(
            state: SeriesDetailUiState,
            addSubSeries: AddSubSeriesUiState = AddSubSeriesUiState.Closed(),
        ): HTMLElement =
            mounts.mount {
                SeriesDetailPage(
                    state = state,
                    onOpenLibrary = {},
                    onOpenBook = {},
                    onOpenSeries = { opened += it },
                    onToggleSection = { toggled += it },
                    addSubSeries = addSubSeries,
                    onAddSubSeriesEvent = { adderEvents += it },
                )
            }

        fun HTMLElement.texts(selector: String) = querySelectorAll(selector).asList().map { it.textContent.orEmpty() }

        test("a child page's breadcrumb is its series path, the current page marked") {
            val root = page(childEra1())

            val nav = root.querySelector("nav.sd-path") as HTMLElement
            nav.getAttribute("aria-label") shouldBe "Series path"
            nav.texts("li") shouldContainExactly listOf("Library", "Cosmere", "Mistborn", "Mistborn Era 1")
            (nav.querySelector("[aria-current=page]") as HTMLElement).textContent shouldBe "Mistborn Era 1"
        }

        test("each ancestor in the breadcrumb opens its own series") {
            val root = page(childEra1())

            root
                .querySelectorAll("nav.sd-path a")
                .asList()
                .drop(1)
                .forEach { (it as HTMLElement).click() }

            opened shouldBe listOf("cosmere", "mistborn")
        }

        test("the page has one h1, the series' name") {
            val root = page(groupedCosmere())

            root.texts("h1") shouldBe listOf("Cosmere")
        }

        test("a parent's hero counts its sub-series and every book under it") {
            val root = page(groupedCosmere())

            (root.querySelector(".sd-stat") as HTMLElement).textContent.orEmpty() shouldContain "2 series · 5 books"
        }

        // "Continue Book 3" is ambiguous when the books come from several series.
        test("Continue on a parent page names the book and says where it sits") {
            val root = page(groupedCosmere())

            val actions = root.querySelector(".sd-actions") as HTMLElement
            (actions.querySelector("button") as HTMLElement).textContent.orEmpty() shouldContain "Continue The Hero of Ages"
            (actions.querySelector(".sd-resume-where") as HTMLElement).textContent shouldBe "Mistborn Era 1 · Book 3"
        }

        // The same words Android and iOS show: Start, the book, and where it sits underneath.
        test("an unstarted parent page's button starts the book it names") {
            val root = page(unstartedCosmere())

            val actions = root.querySelector(".sd-actions") as HTMLElement
            (actions.querySelector("button") as HTMLElement).textContent.orEmpty().trim() shouldBe "Start The Final Empire"
            (actions.querySelector(".sd-resume-where") as HTMLElement).textContent shouldBe "Mistborn Era 1 · Book 1"
        }

        test("a flat child page says which book Continue resumes") {
            val root = page(childEra1())

            (root.querySelector(".sd-actions button") as HTMLElement).textContent.orEmpty().trim() shouldBe "Continue Book 3"
            root.querySelector(".sd-resume-where") shouldBe null
        }

        test("sub-series are cards with their count and progress, in sibling order") {
            val root = page(groupedCosmere())

            root.texts(".sd-sub-n") shouldContainExactly listOf("Mistborn", "Elantris")
            root.texts(".sd-sub-m") shouldContainExactly listOf("3 books · 2 finished", "1 book · Finished")
        }

        test("a sub-series that holds series of its own wears a stack and says how many") {
            val root = page(groupedCosmere())

            val stacked = root.querySelectorAll(".sd-sub.is-stack").asList()
            stacked.size shouldBe 1
            (stacked.single() as HTMLElement).querySelector(".sd-sub-stack")?.textContent shouldBe "1 series"
        }

        test("a sub-series card opens that series") {
            val root = page(groupedCosmere())

            (root.querySelectorAll(".sd-sub").item(1) as HTMLElement).click()

            opened shouldBe listOf("elantris")
        }

        test("a reader who can't edit sees no Add sub-series tile") {
            val root = page(groupedCosmere(canEditMetadata = false))

            root.querySelector(".sd-sub-add") shouldBe null
        }

        test("an editor's Add sub-series tile opens the dialog") {
            val root = page(groupedCosmere(canEditMetadata = true))

            (root.querySelector(".sd-sub-add") as HTMLElement).click()

            adderEvents shouldBe listOf(AddSubSeriesEvent.Opened)
        }

        test("an editor can start a hierarchy from a flat series: the tile shows with no sub-series yet") {
            val root = page(childEra1().copy(canEditMetadata = true))

            (root.querySelector(".sd-sub-add") as HTMLElement).click()

            adderEvents shouldBe listOf(AddSubSeriesEvent.Opened)
        }

        test("a flat series shows no Sub-series panel to a reader who can't edit") {
            val root = page(childEra1())

            root.querySelector(".sd-subs") shouldBe null
        }

        test("offline, the Add sub-series tile is disabled — the change needs the server") {
            val root = page(groupedCosmere(canEditMetadata = true, isOnline = false))

            (root.querySelector(".sd-sub-add") as HTMLButtonElement).disabled shouldBe true
        }

        test("the books are grouped under each sub-series, nested ones a level deeper, own books last") {
            val root = page(groupedCosmere(elantrisCollapsed = false))

            root.texts(".sd-group-t") shouldContainExactly listOf("Mistborn", "Mistborn Era 1", "Elantris", "Also in Cosmere")
            root.texts(".sd-groups h3") shouldContainExactly listOf("Mistborn", "Elantris", "Also in Cosmere")
            root.texts(".sd-groups h4") shouldContainExactly listOf("Mistborn Era 1")
        }

        test("each group lists its own books, numbered in its own series") {
            val root = page(groupedCosmere(elantrisCollapsed = false))

            val groups = root.querySelectorAll(".sd-group").asList().map { it as HTMLElement }
            groups[1].texts(".sd-book-t") shouldContainExactly listOf("The Final Empire", "The Well of Ascension", "The Hero of Ages")
            groups[1].texts(".sd-seq") shouldContainExactly listOf("#1", "#2", "#3")
            groups[3].texts(".sd-book-t") shouldContainExactly listOf("Warbreaker")
        }

        test("a sub-series heading opens that sub-series; 'Also in' is not a link") {
            val root = page(groupedCosmere())

            root.querySelectorAll(".sd-group-link").asList().forEach { (it as HTMLElement).click() }

            opened shouldBe listOf("mistborn", "era1", "elantris")
        }

        test("a folded group shows its heading and a 'Show all N' that unfolds it") {
            val root = page(groupedCosmere(elantrisCollapsed = true))

            val elantris = root.querySelectorAll(".sd-group").asList().map { it as HTMLElement }[2]
            elantris.querySelectorAll(".sd-book").length shouldBe 0
            val showAll = elantris.querySelector(".sd-show-all") as HTMLElement
            showAll.textContent.orEmpty().trim() shouldBe "Show all 1"
            showAll.click()

            toggled shouldBe listOf("elantris")
        }

        test("an open sub-series group can be folded") {
            val root = page(groupedCosmere(elantrisCollapsed = false))

            val fold = root.querySelector(".sd-group-fold").shouldNotBeNull() as HTMLElement
            fold.getAttribute("aria-expanded") shouldBe "true"
            fold.click()

            toggled shouldBe listOf("mistborn")
        }

        test("a flat page has no group headings at all") {
            val root = page(childEra1())

            root.querySelector(".sd-group-h") shouldBe null
            root.querySelectorAll(".sd-book").length shouldBe 3
        }

        test("the Add sub-series dialog opens over the page when the ViewModel says so") {
            val root =
                page(
                    groupedCosmere(canEditMetadata = true),
                    addSubSeries =
                        AddSubSeriesUiState.Open(
                            parentName = "Cosmere",
                            query = "",
                            candidates = emptyList(),
                            pendingMove = null,
                            newSeries = null,
                            isBusy = false,
                            error = null,
                        ),
                )

            (root.querySelector("dialog .dlg-t") as HTMLElement).textContent shouldBe "Add sub-series to Cosmere"
        }
    })
