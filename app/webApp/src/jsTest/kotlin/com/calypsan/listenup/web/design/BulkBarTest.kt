package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { WebAppSurface { content() } }
    return host
}

class BulkBarTest :
    FunSpec({

        test("the bar states how many rows are selected") {
            val host = mount { BulkBar(count = 2, onClear = {}) }

            (host.querySelector(".bulk") as HTMLElement).textContent.orEmpty() shouldContain "2 selected"
        }

        test("an action reports its click") {
            var fired = 0
            val host =
                mount {
                    BulkBar(
                        count = 3,
                        actions = listOf(BulkAction("Merge", WebIcon.Merge) { fired++ }),
                        onClear = {},
                    )
                }

            (host.querySelector(".bulk .bulk-b") as HTMLElement).click()

            fired shouldBe 1
        }

        test("clear is always offered and reports") {
            var cleared = 0
            val host = mount { BulkBar(count = 5, onClear = { cleared++ }) }

            (host.querySelector(".bulk .bulk-x") as HTMLElement).click()

            cleared shouldBe 1
        }
    })
