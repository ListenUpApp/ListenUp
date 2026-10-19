package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

class BulkBarTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun mount(content: @Composable () -> Unit): HTMLElement = mounts.mount { WebAppSurface { content() } }

        test("the bar sticks to the viewport instead of sitting at the end of the page") {
            // ⛔ The bug this pins, reported from a real library. Every other spec here asserts the
            // bar's CONTENT and CALLBACKS — one is even called "clear is always offered and
            // reports" — and all of them passed while the bar was unreachable. It had a pill radius
            // and an 18px/48px drop shadow but NO positioning, so it rendered in flow at the very
            // end of the page: on a thousand-book library, a scroll to the bottom away. To the
            // reader that is "multi-select cannot be turned off and shows no actions", because
            // Clear and every action live in this bar.
            //
            // Markup is not reach. This asserts the computed position, which is the property that
            // was missing and the only one a content assertion cannot see.
            val host = mount { BulkBar(count = 2, label = "2 books selected", onClear = {}) }

            val bar = host.querySelector(".bulk") as HTMLElement
            window.getComputedStyle(bar).position shouldBe "sticky"
        }

        test("the bar states how many rows are selected") {
            val host = mount { BulkBar(count = 2, label = "2 books selected", onClear = {}) }

            (host.querySelector(".bulk") as HTMLElement).textContent.orEmpty() shouldContain "2 selected"
        }

        test("an action reports its click") {
            var fired = 0
            val host =
                mount {
                    BulkBar(
                        count = 3,
                        label = "3 books selected",
                        actions = listOf(BulkAction("Merge", WebIcon.Merge) { fired++ }),
                        onClear = {},
                    )
                }

            (host.querySelector(".bulk .bulk-b") as HTMLElement).click()

            fired shouldBe 1
        }

        test("clear is always offered and reports") {
            var cleared = 0
            val host = mount { BulkBar(count = 5, label = "5 books selected", onClear = { cleared++ }) }

            (host.querySelector(".bulk .bulk-x") as HTMLElement).click()

            cleared shouldBe 1
        }

        test("the bar is a labelled toolbar: Cancel selection first, the count, full action labels") {
            val host =
                mount {
                    BulkBar(
                        count = 2,
                        label = "2 books selected",
                        actions = listOf(BulkAction("Add to shelf") {}),
                        onClear = {},
                    )
                }
            val bar = host.querySelector("[role=toolbar]") as HTMLElement
            bar.getAttribute("aria-label") shouldBe "2 books selected"
            (bar.firstElementChild as HTMLElement).getAttribute("aria-label") shouldBe "Cancel selection"
            bar.textContent.orEmpty() shouldContain "Add to shelf"
        }

        test("a book selection's toolbar is named for how many books") {
            booksSelectedLabel(1) shouldBe "1 book selected"
            booksSelectedLabel(3) shouldBe "3 books selected"
        }
    })
