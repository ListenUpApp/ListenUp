package com.calypsan.listenup.web.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.nav.FocusPageOnNavigation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

/**
 * The page's name, said once: one H1, the tab's title, and the place keyboard focus lands.
 *
 * Every page used to roll its own title — 31 rules drifting between 1.5rem and 2.1rem, and a few
 * pages with no H1 at all while they loaded. One composable means one size, one heading, and one
 * focus target, whatever state the page is in.
 */
class PageHeaderTest :
    FunSpec({
        val mounts = MountRegistry()
        var originalTitle = ""

        beforeTest { originalTitle = document.title }
        afterTest {
            mounts.disposeAll()
            document.title = originalTitle
        }

        test("renders exactly one H1, wearing the one page-title rule") {
            val host = mounts.mount { PageHeader(title = "Collections") }

            host.querySelectorAll("h1").length shouldBe 1
            val h1 = host.querySelector("h1") as HTMLElement
            h1.textContent shouldBe "Collections"
            h1.className shouldBe "page-t"
        }

        test("names the tab after the page, or after the words it is given for the tab") {
            mounts.mount { PageHeader(title = "Collections") }
            awaitFrame()
            document.title shouldBe "Collections · ListenUp"

            mounts.disposeAll()
            mounts.mount { PageHeader(title = "The Hobbit", documentTitle = "Edit The Hobbit") }
            awaitFrame()
            document.title shouldBe "Edit The Hobbit · ListenUp"
        }

        test("eyebrow and subtitle sit around the heading, and actions sit beside it") {
            val host =
                mounts.mount {
                    PageHeader(
                        title = "Inbox",
                        eyebrow = "Admin",
                        subtitle = "Books that need a look.",
                        actions = { Button(attrs = { classes("probe-act") }) { Text("Release") } },
                    )
                }

            host.querySelector(".page-eyebrow")!!.textContent shouldBe "Admin"
            host.querySelector(".page-sub")!!.textContent shouldBe "Books that need a look."
            host.querySelector(".page-h-actions .probe-act") shouldNotBe null
            // The eyebrow is a label, not a heading: the outline still has one entry for the page.
            host.querySelectorAll("h1, h2, h3").length shouldBe 1
        }

        test("a page still loading keeps its H1, named, with a skeleton where the words will go") {
            val host = mounts.mount { PageHeader(title = "Profile", pending = true) }

            val h1 = host.querySelector("h1") as HTMLElement
            h1.querySelector(".sr-only")!!.textContent shouldBe "Profile"
            h1.querySelector(".skel")!!.getAttribute("aria-hidden") shouldBe "true"
        }

        test("the header's H1 is where focus lands when the page changes") {
            var page by mutableStateOf("first")
            val host =
                mounts.mount {
                    Main(attrs = { id("page-header-probe-main") }) {
                        FocusPageOnNavigation(pageKey = page) {
                            document.getElementById("page-header-probe-main") as? HTMLElement
                        }
                        PageHeader(title = if (page == "first") "Library" else "Series")
                    }
                }
            awaitFrame()

            page = "second"
            repeat(FOCUS_FRAMES) { awaitFrame() }

            document.activeElement shouldBe host.querySelector("h1.page-t")
            (document.activeElement as HTMLElement).textContent shouldBe "Series"
        }

        test("focus follows the heading when the loading header gives way to the real one") {
            // A detail page renders a pending header, then its hero's header in a different place in
            // the tree. The first H1 is gone; focus must not be left stranded on <body>.
            var page by mutableStateOf("first")
            var loaded by mutableStateOf(false)
            val host =
                mounts.mount {
                    Main(attrs = { id("page-header-probe-main") }) {
                        FocusPageOnNavigation(pageKey = page) {
                            document.getElementById("page-header-probe-main") as? HTMLElement
                        }
                        if (loaded) {
                            org.jetbrains.compose.web.dom
                                .Div { PageHeader(title = "The Hobbit") }
                        } else {
                            PageHeader(title = "Book", pending = true)
                        }
                    }
                }
            awaitFrame()

            page = "second"
            repeat(2) { awaitFrame() }
            loaded = true
            repeat(FOCUS_FRAMES) { awaitFrame() }

            (document.activeElement as HTMLElement).textContent shouldBe "The Hobbit"
            document.activeElement shouldBe host.querySelector("h1")
        }
    })

private const val FOCUS_FRAMES = 6
