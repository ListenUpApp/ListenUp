package com.calypsan.listenup.web.features.seriesedit

import androidx.compose.runtime.mutableStateOf
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesEvent
import com.calypsan.listenup.client.presentation.seriesedit.ParentPickerDisabledReason
import com.calypsan.listenup.client.presentation.seriesedit.ParentPickerRow
import com.calypsan.listenup.client.presentation.seriesedit.SeriesCandidate
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiEvent
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiState
import com.calypsan.listenup.client.presentation.seriesedit.ExistingSeriesMatch
import com.calypsan.listenup.client.presentation.seriesedit.NewSeriesDraft
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event
import org.w3c.dom.EventInit
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

/**
 * The editor's "Place in library" section and the "Move into…" tree it opens.
 *
 * What these pin: where the series sits, and the way to move it; the sub-series list reorders by
 * button and by drag, always as ONE whole-order event, announced, focus following the moved row;
 * offline every hierarchy control is disabled and a banner says why; the tree is a real
 * `role="tree"` whose disabled rows stay visible with their reason; the arrows, Right/Left and Enter
 * drive it; and the New parent dialog offers an existing series rather than failing.
 */
class PlaceInLibraryTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        val events = mutableListOf<SeriesEditUiEvent>()
        val adderEvents = mutableListOf<AddSubSeriesEvent>()
        beforeTest {
            events.clear()
            adderEvents.clear()
        }

        val children =
            listOf(
                SeriesCandidate(SeriesId("era1"), "Mistborn Era 1", 4),
                SeriesCandidate(SeriesId("era2"), "Mistborn Era 2", 4),
                SeriesCandidate(SeriesId("era3"), "Mistborn Era 3", 1),
            )

        fun mistborn(
            parentId: String? = "cosmere",
            parentName: String? = "Cosmere",
            isOnline: Boolean = true,
            childSeries: List<SeriesCandidate> = children,
            pickerVisible: Boolean = false,
            parentQuery: String = "",
            newParent: NewSeriesDraft? = null,
        ) = SeriesEditUiState(
            isLoading = false,
            seriesId = "mistborn",
            name = "Mistborn",
            parentId = parentId,
            parentName = parentName,
            childSeries = childSeries,
            isOnline = isOnline,
            parentPickerVisible = pickerVisible,
            parentQuery = parentQuery,
            newParent = newParent,
        )

        fun page(
            state: SeriesEditUiState,
            rows: List<ParentPickerRow> = emptyList(),
        ): HTMLElement =
            mounts.mount {
                SeriesEditPage(
                    state = state,
                    mergeCandidates = emptyList(),
                    mergeHistory =
                        com.calypsan.listenup.client.presentation.merge.MergeHistoryState
                            .Ready(emptyList()),
                    onEvent = { events += it },
                    onMergeQuery = {},
                    parentPickerRows = rows,
                    onAddSubSeriesEvent = { adderEvents += it },
                )
            }

        fun HTMLElement.button(label: String): HTMLButtonElement =
            querySelectorAll("button").asList().map { it as HTMLButtonElement }.first {
                it.textContent?.trim() == label || it.getAttribute("aria-label") == label
            }

        fun HTMLElement.press(key: String) {
            dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))
        }

        test("the section says where the series sits and offers to move it") {
            val root = page(mistborn())

            (root.querySelector(".sh-partof-v") as HTMLElement).textContent shouldBe "Cosmere"
            root.button("Move into…").click()

            events shouldBe listOf(SeriesEditUiEvent.ParentPickerOpened)
        }

        test("a top-level series says so") {
            val root = page(mistborn(parentId = null, parentName = null))

            (root.querySelector(".sh-partof-v") as HTMLElement).textContent shouldBe "Top level"
        }

        test("the caption says hierarchy changes apply at once and need the server") {
            val root = page(mistborn())

            root.textContent.orEmpty() shouldContain "Saved as soon as you choose. Needs a connection to the server."
        }

        test("the sub-series are listed in order, with their counts, under an h3") {
            val root = page(mistborn())

            (root.querySelector(".sh-subs-t") as HTMLElement).tagName shouldBe "H3"
            root.querySelectorAll(".sh-sub-n").asList().map { it.textContent } shouldContainExactly
                listOf("Mistborn Era 1", "Mistborn Era 2", "Mistborn Era 3")
            root.querySelectorAll(".sh-sub-m").asList().map { it.textContent } shouldContainExactly
                listOf("4 books", "4 books", "1 book")
        }

        test("Move later sends the whole new order once, and announces where the row went") {
            val root = page(mistborn())

            val first = root.querySelector("[data-sub-id=era1]") as HTMLElement
            (first.querySelector(".sh-later") as HTMLElement).click()
            awaitFrame()

            events shouldBe listOf(SeriesEditUiEvent.ChildSeriesReordered(listOf("era2", "era1", "era3")))
            (root.querySelector("[aria-live=polite]") as HTMLElement).textContent shouldBe
                "Mistborn Era 1 moved to position 2 of 3"
        }

        test("the ends can't move further") {
            val root = page(mistborn())

            ((root.querySelector("[data-sub-id=era1]") as HTMLElement).querySelector(".sh-earlier") as HTMLButtonElement)
                .disabled shouldBe true
            ((root.querySelector("[data-sub-id=era3]") as HTMLElement).querySelector(".sh-later") as HTMLButtonElement)
                .disabled shouldBe true
        }

        test("focus follows the moved row once the new order arrives") {
            val state = mutableStateOf(mistborn())
            val root =
                mounts.mount {
                    SeriesEditPage(
                        state = state.value,
                        mergeCandidates = emptyList(),
                        mergeHistory =
                            com.calypsan.listenup.client.presentation.merge.MergeHistoryState
                                .Ready(emptyList()),
                        onEvent = { event ->
                            if (event is SeriesEditUiEvent.ChildSeriesReordered) {
                                state.value =
                                    state.value.copy(
                                        childSeries = event.orderedChildIds.map { id -> children.first { it.id.value == id } },
                                    )
                            }
                        },
                        onMergeQuery = {},
                    )
                }

            ((root.querySelector("[data-sub-id=era1]") as HTMLElement).querySelector(".sh-later") as HTMLElement).click()
            awaitFrame()
            awaitFrame()

            val focused = document.activeElement as HTMLElement
            focused.classList.contains("sh-later") shouldBe true
            (focused.closest("[data-sub-id]") as HTMLElement).getAttribute("data-sub-id") shouldBe "era1"
        }

        test("dropping a dragged row sends the whole new order once") {
            val root = page(mistborn())

            val grip = (root.querySelector("[data-sub-id=era3]") as HTMLElement).querySelector(".sh-grip") as HTMLElement
            grip.getAttribute("draggable") shouldBe "true"
            grip.dispatchEvent(Event("dragstart", EventInit(bubbles = true)))
            awaitFrame()
            (root.querySelector("[data-sub-id=era1]") as HTMLElement).dispatchEvent(
                Event("drop", EventInit(bubbles = true, cancelable = true)),
            )

            events shouldBe listOf(SeriesEditUiEvent.ChildSeriesReordered(listOf("era3", "era1", "era2")))
        }

        test("Add sub-series opens the shared dialog") {
            val root = page(mistborn())

            root.button("Add sub-series").click()

            adderEvents shouldBe listOf(AddSubSeriesEvent.Opened)
        }

        test("offline, a banner says why and every hierarchy control is disabled") {
            val root = page(mistborn(isOnline = false))

            root.textContent.orEmpty() shouldContain "You're offline"
            root.button("Move into…").disabled shouldBe true
            root.button("Add sub-series").disabled shouldBe true
            root.querySelectorAll(".sh-earlier, .sh-later").asList().all { (it as HTMLButtonElement).disabled } shouldBe true
            (root.querySelector(".sh-grip") as HTMLElement).hasAttribute("draggable") shouldBe false
        }

        val treeRows =
            listOf(
                ParentPickerRow("cosmere", "Cosmere", 0, emptyList(), 23, 2, true, ParentPickerDisabledReason.CURRENT_PARENT),
                ParentPickerRow("mistborn", "Mistborn", 1, listOf("Cosmere"), 8, 2, true, ParentPickerDisabledReason.THIS_SERIES),
                ParentPickerRow(
                    "era1",
                    "Mistborn Era 1",
                    2,
                    listOf("Cosmere", "Mistborn"),
                    4,
                    0,
                    false,
                    ParentPickerDisabledReason.INSIDE_THIS_SERIES,
                ),
                ParentPickerRow("storm", "The Stormlight Archive", 1, listOf("Cosmere"), 7, 0, false, null),
                ParentPickerRow("disc", "Discworld", 0, emptyList(), 41, 5, false, null),
            )

        fun HTMLElement.node(id: String) = querySelector("[role=treeitem][data-node-id=$id]") as HTMLElement

        test("Move into… is a tree in a dialog, titled with the series' name") {
            val root = page(mistborn(pickerVisible = true), treeRows)

            (root.querySelector("dialog .dlg-t") as HTMLElement).textContent shouldBe "Move “Mistborn” into…"
            root.querySelectorAll("[role=tree] [role=treeitem]").length shouldBe 5
            root.node("era1").getAttribute("aria-level") shouldBe "3"
            root.node("cosmere").getAttribute("aria-expanded") shouldBe "true"
            root.node("disc").getAttribute("aria-expanded") shouldBe "false"
            root.node("storm").hasAttribute("aria-expanded") shouldBe false
        }

        test("rows that can't be chosen stay in the tree, disabled, each tied to its reason") {
            val root = page(mistborn(pickerVisible = true), treeRows)

            listOf("cosmere" to "Current", "mistborn" to "This series", "era1" to "Inside Mistborn").forEach { (id, reason) ->
                val node = root.node(id)
                node.getAttribute("aria-disabled") shouldBe "true"
                (root.querySelector("#${node.getAttribute("aria-describedby")}") as HTMLElement).textContent shouldBe reason
            }
            root.node("storm").hasAttribute("aria-disabled") shouldBe false
            root.node("disc").textContent.orEmpty() shouldContain "5 series"
            root.node("storm").textContent.orEmpty() shouldContain "7 books"
        }

        test("choosing a row moves the series there; a disabled row does nothing") {
            val root = page(mistborn(pickerVisible = true), treeRows)

            root.node("era1").click()
            root.node("storm").click()

            events shouldBe listOf(SeriesEditUiEvent.ParentSelected("storm"))
        }

        test("one row is the tab stop, starting on the first that can be chosen") {
            val root = page(mistborn(pickerVisible = true), treeRows)

            val stops = root.querySelectorAll("[role=treeitem][tabindex='0']").asList()
            stops.map { (it as HTMLElement).getAttribute("data-node-id") } shouldBe
                listOf("storm")
        }

        test("the arrows move, Right expands, Left collapses, Enter chooses") {
            val root = page(mistborn(pickerVisible = true), treeRows)

            root.node("storm").focus()
            root.node("storm").press("ArrowDown")
            (document.activeElement as HTMLElement).getAttribute("data-node-id") shouldBe "disc"

            root.node("disc").press("ArrowRight")
            root.node("cosmere").press("ArrowLeft")
            root.node("disc").press("Enter")
            root.node("era1").press("Enter")

            events shouldBe
                listOf(
                    SeriesEditUiEvent.ParentPickerNodeToggled("disc"),
                    SeriesEditUiEvent.ParentPickerNodeToggled("cosmere"),
                    SeriesEditUiEvent.ParentSelected("disc"),
                )
        }

        test("Left on a collapsed row steps back to its parent") {
            val root = page(mistborn(pickerVisible = true), treeRows)

            root.node("era1").press("ArrowLeft")

            (document.activeElement as HTMLElement).getAttribute("data-node-id") shouldBe "mistborn"
        }

        test("Top level is offered unless the series is already there") {
            val root = page(mistborn(pickerVisible = true), treeRows)

            root.button("Top level (no parent)").click()

            events shouldBe listOf(SeriesEditUiEvent.ParentCleared)
        }

        test("a top-level series sees Top level as Current, and can't choose it") {
            val root = page(mistborn(parentId = null, parentName = null, pickerVisible = true), treeRows)

            val top = root.button("Top level (no parent)Current")
            top.getAttribute("aria-disabled") shouldBe "true"
            top.click()
            events shouldBe emptyList()
        }

        test("while searching, each row says where it sits") {
            val flat = treeRows.map { it.copy(depth = 0) }
            val root = page(mistborn(pickerVisible = true, parentQuery = "a"), flat)

            root.node("era1").textContent.orEmpty() shouldContain "in Cosmere › Mistborn"
            root.node("cosmere").hasAttribute("aria-expanded") shouldBe false
        }

        test("a search that matches nothing says so") {
            val root = page(mistborn(pickerVisible = true, parentQuery = "zzz"), emptyList())

            root.textContent.orEmpty() shouldContain "No series match that search."
        }

        test("New parent series… starts the dialog") {
            val root = page(mistborn(pickerVisible = true), treeRows)

            root.button("New parent series…").click()

            events shouldBe listOf(SeriesEditUiEvent.NewParentStarted)
        }

        test("the New parent dialog says what it will do, and creates and moves") {
            val root = page(mistborn(newParent = NewSeriesDraft("Scadrial")))

            root.textContent.orEmpty() shouldContain "Creates “Scadrial” and moves Mistborn into it."
            root.button("Create and move").click()

            events shouldBe listOf(SeriesEditUiEvent.NewParentConfirmed)
        }

        test("a new parent name that exists offers to move into it instead") {
            val draft = NewSeriesDraft("Cosmere", existing = ExistingSeriesMatch("cosmere-2", "Cosmere", isSelectable = true))
            val root = page(mistborn(parentId = null, parentName = null, newParent = draft))

            root.button("Create and move").disabled shouldBe true
            root.textContent.orEmpty() shouldContain "“Cosmere” already exists."
            root.button("Move into it instead").click()

            events shouldContainExactly
                listOf(SeriesEditUiEvent.ParentSelected("cosmere-2"), SeriesEditUiEvent.NewParentDismissed)
        }
    })
