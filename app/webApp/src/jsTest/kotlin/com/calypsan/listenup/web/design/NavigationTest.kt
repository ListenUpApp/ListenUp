package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { content() }
    return host
}

private val TABS =
    listOf(
        TabItem("overview", "Overview"),
        TabItem("chapters", "Chapters", count = "44"),
        TabItem("files", "Files", count = "3"),
    )

class NavigationTest :
    FunSpec({

        test("exactly one tab is active") {
            val host = mount { Tabs(TABS, active = "chapters") }

            host.querySelectorAll(".tab").length shouldBe TABS.size
            host.querySelectorAll(".tab.on").length shouldBe 1
        }

        test("selecting a tab reports its key, not its index") {
            // The key is what goes in the URL (?tab=chapters), so an index here would make the
            // page contract depend on tab order.
            var selected: String? = null
            val host = mount { Tabs(TABS, active = "overview") { selected = it } }

            (host.querySelectorAll(".tab").item(2) as HTMLElement).click()

            selected shouldBe "files"
        }

        test("only counted tabs carry a badge") {
            val host = mount { Tabs(TABS, active = "overview") }

            host.querySelectorAll(".tab .ct").length shouldBe 2
        }

        test("a segmented control marks its active choice") {
            val host =
                mount {
                    SegmentedControl(
                        listOf(SegmentItem("all", "All 44"), SegmentItem("unheard", "Unheard 35")),
                        active = "unheard",
                    )
                }

            host.querySelectorAll(".seg b.on").length shouldBe 1
            (host.querySelector(".seg b.on") as HTMLElement).textContent shouldBe "Unheard 35"
        }

        test("a plain pill has no dismiss affordance") {
            val host = mount { Pill("Horror") }

            host.querySelectorAll(".pill .x").length shouldBe 0
        }

        test("removing a filter chip does not also toggle it") {
            // The dismiss sits inside the pill, so without stopPropagation the click removes the
            // filter and immediately re-applies it — a bug that looks like nothing happening.
            var toggled = 0
            var removed = 0
            val host =
                mount {
                    Pill("Horror", selected = true, onClick = { toggled++ }, onRemove = { removed++ })
                }

            (host.querySelector(".pill .x") as HTMLElement).click()

            removed shouldBe 1
            toggled shouldBe 0
        }

        test("clicking the pill body still toggles it") {
            var toggled = 0
            val host = mount { Pill("Horror", onClick = { toggled++ }, onRemove = {}) }

            (host.querySelector(".pill") as HTMLElement).click()

            toggled shouldBe 1
        }
    })
