package com.calypsan.listenup.web.shell

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.WebAppSurface
import com.calypsan.listenup.web.design.WebIcon
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
    renderComposable(root = host) { WebAppSurface { content() } }
    return host
}

private val PRIMARY =
    NavSection(
        entries =
            listOf(
                NavEntry("home", "Home", WebIcon.Home),
                NavEntry("library", "Library", WebIcon.Book),
                NavEntry("discover", "Discover", WebIcon.Compass),
            ),
    )

private val YOURS =
    NavSection(
        label = "Yours",
        entries = listOf(NavEntry("shelves", "Shelves", WebIcon.Bookmark)),
    )

private val FOOTER = listOf(NavEntry("settings", "Settings", WebIcon.Cog))

class ShellTest :
    FunSpec({

        test("exactly one nav item is active") {
            val host =
                mount {
                    Shell(sections = listOf(PRIMARY), footer = FOOTER, active = "library") {}
                }

            host.querySelectorAll(".nav-i").length shouldBe 4
            host.querySelectorAll(".nav-i.on").length shouldBe 1
            (host.querySelector(".nav-i.on") as HTMLElement).textContent.orEmpty() shouldContain "Library"
        }

        test("selecting a nav item reports its key, not its index") {
            // The key is what becomes the URL path, so an index would tie the page contract to
            // sidebar order.
            var selected: String? = null
            val host =
                mount {
                    Shell(
                        sections = listOf(PRIMARY),
                        active = "home",
                        onNavigate = { selected = it },
                    ) {}
                }

            (host.querySelectorAll(".nav-i").item(1) as HTMLElement).click()

            selected shouldBe "library"
        }

        test("the content slot renders inside the scrolling main region") {
            val host =
                mount {
                    Shell(sections = listOf(PRIMARY), active = "home") { Text("BODY") }
                }

            val main = host.querySelector(".shell-main") as HTMLElement
            main.textContent.orEmpty() shouldContain "BODY"
        }

        test("collapsing hides the labels but keeps the icons") {
            val host =
                mount {
                    Shell(sections = listOf(PRIMARY), active = "home", collapsed = true) {}
                }

            host.querySelectorAll(".sidebar.clpsd").length shouldBe 1
            host.querySelectorAll(".nav-i .lb").length shouldBe 0
            host.querySelectorAll(".nav-i svg").length shouldBe 3
        }

        test("a group label renders expanded and disappears collapsed") {
            val expanded =
                mount { Shell(sections = listOf(PRIMARY, YOURS), active = "home") {} }
            val collapsed =
                mount {
                    Shell(sections = listOf(PRIMARY, YOURS), active = "home", collapsed = true) {}
                }

            expanded.querySelectorAll(".sb-group").length shouldBe 1
            collapsed.querySelectorAll(".sb-group").length shouldBe 0
        }

        test("the expanded sidebar offers collapse, the collapsed one offers expand") {
            var toggles = 0
            val expanded =
                mount {
                    Shell(sections = listOf(PRIMARY), active = "home", onToggleCollapse = { toggles++ }) {}
                }
            val collapsed =
                mount {
                    Shell(
                        sections = listOf(PRIMARY),
                        active = "home",
                        collapsed = true,
                        onToggleCollapse = { toggles++ },
                    ) {}
                }

            (expanded.querySelector(".sb-toggle") as HTMLElement).click()
            toggles shouldBe 1

            expanded.querySelectorAll(".sb-expand").length shouldBe 0
            (collapsed.querySelector(".sb-expand") as HTMLElement).click()
            toggles shouldBe 2
        }
    })
