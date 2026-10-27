package com.calypsan.listenup.web.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * The Library's one sort control (board `Library-WebDesktop-Sort`): a dropdown naming the sort, with
 * the categories, both directions and — for a title or name sort — Ignore “A”, “An”, “The”.
 */
class SortDropdownTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        suspend fun open(
            state: SortState = SortState(SortCategory.TITLE, SortDirection.ASCENDING),
            ignoreArticles: Boolean? = true,
            onCategory: (SortCategory) -> Unit = {},
            onToggleDirection: () -> Unit = {},
            onToggleIgnore: () -> Unit = {},
        ): HTMLElement {
            val root =
                mounts.mount {
                    SortDropdown(
                        noun = "books",
                        categories =
                            listOf(
                                SortCategory.TITLE,
                                SortCategory.AUTHOR,
                                SortCategory.SERIES,
                                SortCategory.ADDED,
                                SortCategory.DURATION,
                                SortCategory.YEAR,
                                SortCategory.RATING,
                                SortCategory.LISTENER_RATING,
                            ),
                        state = state,
                        onCategory = onCategory,
                        onToggleDirection = onToggleDirection,
                        ignoreArticles = ignoreArticles,
                        onToggleIgnoreArticles = onToggleIgnore,
                    )
                }
            (root.querySelector("button[aria-haspopup=menu]") as HTMLElement).click()
            awaitFrame()
            return root
        }

        test("the trigger names the sort, and the menu offers every category as a radio in board order") {
            val root = open()
            root.querySelector("button[aria-haspopup=menu]")!!.textContent shouldBe "Sort: Title, A–Z"
            root
                .querySelectorAll("[role=group][aria-label=\"Sort by\"] [role=menuitemradio]")
                .asList()
                .map { it.textContent } shouldBe
                listOf("Title", "Author", "Series", "Recently added", "Length", "Year published", "Rating", "Your listeners' rating")
            root.querySelector("[role=menuitemradio][aria-checked=true]")!!.textContent shouldBe "Title"
        }

        test("the trigger's name contains the words on it") {
            val trigger = open().querySelector("button[aria-haspopup=menu]") as HTMLElement
            trigger.getAttribute("aria-label")!!.startsWith("Sort: Title, A–Z") shouldBe true
        }

        test("Ignore articles is a checkbox item that keeps the menu open") {
            var toggled = 0
            val root = open(onToggleIgnore = { toggled++ })
            val box = root.querySelector("[role=menuitemcheckbox]") as HTMLElement
            box.textContent shouldBe "Ignore “A”, “An”, “The”"
            box.getAttribute("aria-checked") shouldBe "true"
            box.click()
            awaitFrame()
            toggled shouldBe 1
            (root.querySelector("[role=menu]") != null) shouldBe true
        }

        test("choosing the other direction toggles once and keeps the menu open; choosing the current one does nothing") {
            var toggles = 0
            val root = open(onToggleDirection = { toggles++ })
            val directions =
                root
                    .querySelectorAll("[role=group][aria-label=\"Direction\"] [role=menuitemradio]")
                    .asList()
                    .map { it as HTMLElement }
            directions.map { it.textContent } shouldBe listOf("A → Z", "Z → A")
            directions[0].click()
            awaitFrame()
            toggles shouldBe 0
            directions[1].click()
            awaitFrame()
            toggles shouldBe 1
            (root.querySelector("[role=menu]") != null) shouldBe true
        }

        test("a direction that re-words the trigger leaves the menu showing, with focus still inside it") {
            val root =
                mounts.mount {
                    var state by remember { mutableStateOf(SortState(SortCategory.TITLE, SortDirection.ASCENDING)) }
                    SortDropdown(
                        noun = "books",
                        categories = listOf(SortCategory.TITLE, SortCategory.AUTHOR),
                        state = state,
                        onCategory = {},
                        onToggleDirection = { state = state.toggleDirection() },
                        ignoreArticles = true,
                        onToggleIgnoreArticles = {},
                    )
                }
            val trigger = root.querySelector("button[aria-haspopup=menu]") as HTMLElement
            trigger.click()
            awaitFrame()
            val descending =
                root.querySelectorAll("[role=group][aria-label=\"Direction\"] [role=menuitemradio]").item(1) as HTMLElement
            descending.focus()
            descending.click()
            awaitFrame()
            awaitFrame()
            trigger.textContent shouldBe "Sort: Title, Z–A"
            val menu = root.querySelector("[role=menu]") as HTMLElement
            menu.asDynamic().matches(":popover-open") as Boolean shouldBe true
            menu.contains(document.activeElement) shouldBe true
        }

        test("choosing a category reports it and closes the menu") {
            var chosen: SortCategory? = null
            val root = open(onCategory = { chosen = it })
            (root.querySelectorAll("[role=menuitemradio]").item(1) as HTMLElement).click()
            awaitFrame()
            chosen shouldBe SortCategory.AUTHOR
            root.querySelector("[role=menu]") shouldBe null
        }

        test("a length sort names its direction in its own words") {
            val root = open(state = SortState(SortCategory.DURATION, SortDirection.DESCENDING), ignoreArticles = null)
            root.querySelector("button[aria-haspopup=menu]")!!.textContent shouldBe "Sort: Length, Longest"
            root.querySelector("[role=menuitemcheckbox]") shouldBe null
        }

        test("without a status (Series, Authors, Narrators) the trigger reads Sort: and there is no Show group") {
            val root = open()
            (root.querySelector(".lib-sort-trigger") as HTMLElement).textContent shouldBe "Sort: Title, A–Z"
            document.querySelectorAll("[role=menu] [role=group]").asList().none {
                (it as HTMLElement).getAttribute("aria-label") == "Show"
            } shouldBe true
        }
    })
