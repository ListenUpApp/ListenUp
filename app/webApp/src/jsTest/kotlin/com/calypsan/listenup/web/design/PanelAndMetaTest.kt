package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { content() }
    return host
}

private val META =
    listOf(
        MetaEntry("Narrator", "Santino Fontana"),
        MetaEntry("Duration", "18:40:11", machine = true),
        MetaEntry("Path", "/media/audiobooks/king/the-institute/", machine = true),
    )

class PanelAndMetaTest :
    FunSpec({

        test("a titled panel renders its heading and body") {
            val host = mount { Panel(title = "Details") { Text("body") } }

            host.querySelector("h3")!!.textContent shouldBe "Details"
            host.textContent!! shouldContain "body"
        }

        test("an untitled panel has no header at all") {
            // Not an empty header — an absent one. An empty 46px bar reads as a rendering bug.
            val host = mount { Panel { Text("body") } }

            host.querySelectorAll("header").length shouldBe 0
        }

        test("a flush panel drops its body padding for tables that draw their own edges") {
            val flush = mount { Panel(title = "Chapters", flush = true) { Text("t") } }
            val padded = mount { Panel(title = "Chapters") { Text("t") } }

            (flush.querySelector("section > div") as HTMLElement).style.padding shouldBe "0px"
            (padded.querySelector("section > div") as HTMLElement).style.padding shouldBe "18px"
        }

        test("machine-produced values take the mono face, human ones do not") {
            // This is the signal that makes a column of durations scannable; losing it is a
            // silent downgrade, since the text still reads correctly.
            val host = mount { MetaList(META) }

            val values = host.querySelectorAll("dd")
            (values.item(0) as HTMLElement).className shouldBe ""
            (values.item(1) as HTMLElement).className shouldBe "mono"
        }

        test("the details list is a real description list") {
            val host = mount { MetaList(META) }

            host.querySelectorAll("dl dt").length shouldBe META.size
            host.querySelectorAll("dl dd").length shouldBe META.size
        }

        test("the current breadcrumb entry is not a link") {
            val host = mount { Breadcrumb(listOf("Library", "Horror", "The Institute")) }

            host.querySelectorAll("a").length shouldBe 2
            host.querySelector(".cur")!!.textContent shouldBe "The Institute"
        }

        test("clicking a breadcrumb ancestor reports its position") {
            var navigated = -1
            val host = mount { Breadcrumb(listOf("Library", "Horror", "The Institute")) { navigated = it } }

            (host.querySelectorAll("a").item(1) as HTMLElement).click()

            navigated shouldBe 1
        }
    })
