package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

/**
 * "Nothing here", said one way.
 *
 * There was one `.empty` rule and 22 private variants of it, each choosing its own padding, colour and
 * heading level — and loading drew a bare "Loading…" in an empty-state block, which reserves the
 * wrong shape and then jumps.
 */
class EmptyStateTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("renders its heading, its body and its action") {
            var pressed = 0
            val host =
                mounts.mount {
                    EmptyState(title = "Inbox empty", body = "New books will appear here.", icon = WebIcon.Book) {
                        Button(attrs = { onClick { pressed++ } }) { Text("Scan now") }
                    }
                }

            (host.querySelector(".empty h2") as HTMLElement).textContent shouldBe "Inbox empty"
            (host.querySelector(".empty p") as HTMLElement).textContent shouldBe "New books will appear here."
            host.querySelector(".empty .ico svg") shouldNotBe null
            (host.querySelector(".empty .empty-act button") as HTMLElement).click()
            pressed shouldBe 1
        }

        test("under a page's H1 it is an H2; inside a titled panel it is an H3") {
            val page = mounts.mount { EmptyState(title = "Nothing yet") }
            val panel = mounts.mount { Panel(title = "Shelves") { EmptyState(title = "No shelves yet") } }
            val section = mounts.mount { UnderHeading(level = 2) { EmptyState(title = "Nothing on the go") } }

            page.querySelector("h2")!!.textContent shouldBe "Nothing yet"
            panel.querySelector("h3")!!.textContent shouldBe "No shelves yet"
            section.querySelector("h3")!!.textContent shouldBe "Nothing on the go"
        }

        test("an inline note is one quiet sentence, not a heading in the outline") {
            val host = mounts.mount { EmptyState(title = "No devices signed in.", look = EmptyLook.Inline) }

            host.querySelectorAll("h1, h2, h3, h4, h5, h6").length shouldBe 0
            (host.querySelector("p.empty-line") as HTMLElement).textContent shouldBe "No devices signed in."
        }

        test("the inset look keeps the parts and sits on an inset card") {
            val host = mounts.mount { EmptyState(title = "No chapters yet", body = "Add one.", look = EmptyLook.Inset) }

            host.querySelector(".empty.is-inset h2") shouldNotBe null
        }

        test("loading is a skeleton, with the words kept for a screen reader") {
            val host = mounts.mount { LoadingState() }

            val status = host.querySelector("[role=status]") as HTMLElement
            status.textContent shouldBe "Loading…"
            status.querySelector(".sr-only")!!.textContent shouldBe "Loading…"
            status.querySelector(".skel")!!.getAttribute("aria-hidden") shouldBe "true"
            host.querySelector(".empty") shouldBe null
        }
    })
