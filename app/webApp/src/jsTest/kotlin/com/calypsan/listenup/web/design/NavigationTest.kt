package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import com.calypsan.listenup.web.awaitFrame
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

private val TABS =
    listOf(
        TabItem("overview", "Overview"),
        TabItem("chapters", "Chapters", count = "44"),
        TabItem("files", "Files", count = "3"),
    )

class NavigationTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("exactly one tab is active") {
            val host = mounts.mount { Tabs(TABS, active = "chapters", idBase = "t") }

            host.querySelectorAll(".tab").length shouldBe TABS.size
            host.querySelectorAll(".tab.on").length shouldBe 1
        }

        test("selecting a tab reports its key, not its index") {
            // The key is what goes in the URL (?tab=chapters), so an index here would make the
            // page contract depend on tab order.
            var selected: String? = null
            val host = mounts.mount { Tabs(TABS, active = "overview", idBase = "t") { selected = it } }

            (host.querySelectorAll(".tab").item(2) as HTMLElement).click()

            selected shouldBe "files"
        }

        test("only counted tabs carry a badge") {
            val host = mounts.mount { Tabs(TABS, active = "overview", idBase = "t") }

            host.querySelectorAll(".tab .ct").length shouldBe 2
        }

        test("a segmented control marks its active choice") {
            val host =
                mounts.mount {
                    SegmentedControl(
                        listOf(SegmentItem("all", "All 44"), SegmentItem("unheard", "Unheard 35")),
                        active = "unheard",
                        label = "Show",
                    )
                }

            host.querySelectorAll(".seg button.on").length shouldBe 1
            (host.querySelector(".seg button.on") as HTMLElement).textContent shouldBe "Unheard 35"
        }

        test("a toggle pill says whether it is pressed; a pill that goes somewhere does not") {
            val on = mounts.mount { Pill("Books", selected = true, onClick = {}) }
            val off = mounts.mount { Pill("Series", selected = false, onClick = {}) }
            val link = mounts.mount { Pill("Horror", onClick = {}) }

            (on.querySelector(".pill") as HTMLElement).getAttribute("aria-pressed") shouldBe "true"
            (off.querySelector(".pill") as HTMLElement).getAttribute("aria-pressed") shouldBe "false"
            (link.querySelector(".pill") as HTMLElement).hasAttribute("aria-pressed") shouldBe false
        }

        test("a plain pill has no dismiss affordance") {
            val host = mounts.mount { Pill("Horror") }

            host.querySelectorAll(".pill .x").length shouldBe 0
        }

        test("removing a filter chip does not also toggle it") {
            // The dismiss sits inside the pill, so without stopPropagation the click removes the
            // filter and immediately re-applies it — a bug that looks like nothing happening.
            var toggled = 0
            var removed = 0
            val host =
                mounts.mount {
                    Pill("Horror", selected = true, onClick = { toggled++ }, onRemove = { removed++ })
                }

            (host.querySelector(".pill .x") as HTMLElement).click()

            removed shouldBe 1
            toggled shouldBe 0
        }

        test("clicking the pill body still toggles it") {
            var toggled = 0
            val host = mounts.mount { Pill("Horror", onClick = { toggled++ }, onRemove = {}) }

            (host.querySelector(".pill") as HTMLElement).click()

            toggled shouldBe 1
        }
        test("the strip is a tablist of real tab buttons, and only the active one is selected") {
            // ⛔ The tabs were `<div onClick>` — a keyboard reader could see Chapters and Files but
            // never reach them.
            val host = mounts.mount { Tabs(TABS, active = "chapters", idBase = "t") }

            (host.querySelector(".tabs") as HTMLElement).getAttribute("role") shouldBe "tablist"
            val tabs = host.querySelectorAll(".tab").asList().filterIsInstance<HTMLElement>()
            tabs.map { it.tagName } shouldBe List(3) { "BUTTON" }
            tabs.map { it.getAttribute("role") } shouldBe List(3) { "tab" }
            tabs.map { it.getAttribute("aria-selected") } shouldBe listOf("false", "true", "false")
        }

        test("only the active tab is a tab stop; arrows move between the rest") {
            val host = mounts.mount { Tabs(TABS, active = "chapters", idBase = "t") }

            host.tabs().map { it.getAttribute("tabindex") } shouldBe listOf("-1", "0", "-1")
        }

        test("the active tab names the panel it controls, and the panel names it back") {
            val host =
                mounts.mount {
                    Tabs(TABS, active = "files", idBase = "bd")
                    TabPanel(idBase = "bd", key = "files") { Text("FILES") }
                }

            val tab = host.tabs()[2]
            val panel = host.querySelector("[role=tabpanel]") as HTMLElement
            tab.getAttribute("aria-controls") shouldBe panel.id
            panel.getAttribute("aria-labelledby") shouldBe tab.id
        }

        test("arrow keys, Home and End move the selection and the focus, wrapping at the ends") {
            var active by mutableStateOf("overview")
            val host = mounts.mount { Tabs(TABS, active = active, idBase = "t") { active = it } }

            host.tabs()[0].pressKey("ArrowRight")
            awaitFrame()
            active shouldBe "chapters"
            document.activeElement shouldBe host.tabs()[1]

            host.tabs()[1].pressKey("End")
            awaitFrame()
            active shouldBe "files"

            host.tabs()[2].pressKey("ArrowRight")
            awaitFrame()
            active shouldBe "overview"

            host.tabs()[0].pressKey("ArrowLeft")
            awaitFrame()
            active shouldBe "files"

            host.tabs()[2].pressKey("Home")
            awaitFrame()
            active shouldBe "overview"
            document.activeElement shouldBe host.tabs()[0]
        }

        test("a segmented control is a named group of pressed-state buttons") {
            var active by mutableStateOf("all")
            val host =
                mounts.mount {
                    SegmentedControl(
                        listOf(SegmentItem("all", "All 44"), SegmentItem("unheard", "Unheard 35")),
                        active = active,
                        label = "Show",
                    ) { active = it }
                }

            val segments = host.querySelectorAll(".seg button").asList().filterIsInstance<HTMLElement>()
            (host.querySelector(".seg") as HTMLElement).getAttribute("role") shouldBe "group"
            (host.querySelector(".seg") as HTMLElement).getAttribute("aria-label") shouldBe "Show"
            segments.map { it.getAttribute("aria-pressed") } shouldBe listOf("true", "false")

            segments[1].click()
            awaitFrame()
            active shouldBe "unheard"
        }
        test("a filter chip's remove control is a real, named button") {
            val host = mounts.mount { Pill("Horror", selected = true, onClick = {}, onRemove = {}) }

            val remove = host.querySelector(".pill .x") as HTMLElement
            remove.tagName shouldBe "BUTTON"
            remove.getAttribute("type") shouldBe "button"
            remove.getAttribute("aria-label") shouldBe "Remove Horror"
        }
    })

private fun HTMLElement.tabs(): List<HTMLElement> = querySelectorAll(".tab").asList().filterIsInstance<HTMLElement>()

private fun HTMLElement.pressKey(key: String) {
    dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))
}
