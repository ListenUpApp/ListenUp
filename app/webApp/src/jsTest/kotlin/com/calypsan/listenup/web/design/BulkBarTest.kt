package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLElement

class BulkBarTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun mount(content: @Composable () -> Unit): HTMLElement = mounts.mount { WebAppSurface { content() } }

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
