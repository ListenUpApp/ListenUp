package com.calypsan.listenup.web.shell

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.design.WebAppSurface
import com.calypsan.listenup.web.design.WebIcon
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit

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
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun mount(content: @Composable () -> Unit): HTMLElement = mounts.mount { WebAppSurface { content() } }

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

        test("every nav item is a real link to its page") {
            // ⛔ They were `<div onClick>`: unreachable by Tab, announced as nothing. A link is also
            // what lets a reader open Library in a new tab, which a div could never offer.
            val host =
                mount {
                    Shell(sections = listOf(PRIMARY), footer = FOOTER, active = "library", onNavigate = {}) {}
                }

            val items = host.querySelectorAll(".nav-i").asList().filterIsInstance<HTMLElement>()
            items.map { it.tagName } shouldBe List(4) { "A" }
            items.map { it.getAttribute("href") } shouldBe listOf("/home", "/library", "/discover", "/settings")
        }

        test("an entry can name its own address") {
            val host =
                mount {
                    Shell(
                        sections = listOf(NavSection(listOf(NavEntry("home", "Home", WebIcon.Home, href = "/")))),
                        active = "home",
                    ) {}
                }

            (host.querySelector(".nav-i") as HTMLElement).getAttribute("href") shouldBe "/"
        }

        test("only the active item says it is the current page") {
            val host =
                mount {
                    Shell(sections = listOf(PRIMARY), footer = FOOTER, active = "library") {}
                }

            val current = host.querySelectorAll("[aria-current]").asList().filterIsInstance<HTMLElement>()
            current.map { it.textContent.orEmpty().trim() } shouldBe listOf("Library")
            current.single().getAttribute("aria-current") shouldBe "page"
        }

        test("a plain click routes in-app and does not reload the page") {
            var selected: String? = null
            val host =
                mount {
                    Shell(sections = listOf(PRIMARY), active = "home", onNavigate = { selected = it }) {}
                }

            // Read on the way up, then swallowed, so a broken handler fails this spec rather than
            // navigating the whole runner away.
            var preventedByLink: Boolean? = null
            host.addEventListener("click", { event ->
                preventedByLink = event.defaultPrevented
                event.preventDefault()
            })

            (host.querySelectorAll(".nav-i").item(2) as HTMLElement)
                .dispatchEvent(MouseEvent("click", MouseEventInit(bubbles = true, cancelable = true, button = 0)))

            selected shouldBe "discover"
            preventedByLink shouldBe true
        }

        test("a modified click is left to the browser, so a new tab can open") {
            var selected: String? = null
            val host =
                mount {
                    Shell(sections = listOf(PRIMARY), active = "home", onNavigate = { selected = it }) {}
                }
            // Swallow the default in a later listener: the spec must not actually navigate the
            // runner, and a synthetic ctrl-click would otherwise follow the href.
            val link = host.querySelectorAll(".nav-i").item(2) as HTMLElement
            host.addEventListener("click", { it.preventDefault() })

            link.dispatchEvent(MouseEvent("click", MouseEventInit(bubbles = true, cancelable = true, ctrlKey = true)))

            selected shouldBe null
        }

        test("the badge still names its count once the item is a link") {
            val host =
                mount {
                    Shell(
                        sections =
                            listOf(NavSection(listOf(NavEntry("notifications", "Notifications", WebIcon.Bell, badge = 3)))),
                        active = "home",
                    ) {}
                }

            (host.querySelector(".nav-i .nav-badge") as HTMLElement).getAttribute("aria-label") shouldBe "3 unread"
            (host.querySelector(".nav-i") as HTMLElement).getAttribute("title") shouldBe "Notifications"
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
            // The labels stay in the DOM and CSS hides them: the manual `.clpsd` class and the
            // narrow-viewport media query must share ONE rail mechanism, and a media query can
            // only style what is rendered.
            val host =
                mount {
                    Shell(sections = listOf(PRIMARY), active = "home", collapsed = true) {}
                }

            host.querySelectorAll(".sidebar.clpsd").length shouldBe 1
            val labels = host.querySelectorAll(".nav-i .lb")
            labels.length shouldBe 3
            (0 until labels.length).forEach { i ->
                computedDisplay(labels.item(i) as HTMLElement) shouldBe "none"
            }
            host.querySelectorAll(".nav-i svg").length shouldBe 3
        }

        test("labels are visible when expanded") {
            val host = mount { Shell(sections = listOf(PRIMARY), active = "home") {} }

            computedDisplay(host.querySelector(".nav-i .lb") as HTMLElement) shouldBe "block"
        }

        test("a group label shows expanded and hides collapsed") {
            val expanded =
                mount { Shell(sections = listOf(PRIMARY, YOURS), active = "home") {} }
            val collapsed =
                mount {
                    Shell(sections = listOf(PRIMARY, YOURS), active = "home", collapsed = true) {}
                }

            expanded.querySelectorAll(".sb-group").length shouldBe 1
            computedDisplay(expanded.querySelector(".sb-group") as HTMLElement) shouldBe "block"
            computedDisplay(collapsed.querySelector(".sb-group") as HTMLElement) shouldBe "none"
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

/** Computed display of [element] with the design sheet applied — how the rail actually hides. */
private fun computedDisplay(element: HTMLElement): String =
    kotlinx.browser.window
        .getComputedStyle(element)
        .display
