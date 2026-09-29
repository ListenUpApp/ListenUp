package com.calypsan.listenup.web

import com.calypsan.listenup.web.nav.Route
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLElement

/**
 * What changes when the page does: the tab's name, and where keyboard focus lands.
 *
 * A single-page app gets neither for free. The browser only renames the tab and resets focus on a
 * real document load, which this app never does after the first one.
 */
class PageTitleAndFocusTest :
    FunSpec({
        var originalUrl = ""
        var originalTitle = ""

        beforeTest {
            originalUrl = window.location.pathname + window.location.search
            originalTitle = document.title
        }

        afterTest {
            window.history.replaceState(null, "", originalUrl)
            document.title = originalTitle
            (document.activeElement as? HTMLElement)?.blur()
        }

        fun navItem(
            host: HTMLElement,
            label: String,
        ): HTMLElement {
            val items = host.querySelectorAll(".nav-i")
            return (0 until items.length).map { items.item(it) as HTMLElement }.first { it.textContent == label }
        }

        suspend fun awaitFocusOn(selector: String): HTMLElement =
            withTimeout(RECOMPOSE_TIMEOUT_MS) {
                while (document.activeElement?.matches(selector) != true) delay(FOCUS_POLL_MS)
                document.activeElement as HTMLElement
            }

        test("the tab names the page it is showing") {
            val (_, router, composition) = mountAt("/settings")

            try {
                awaitFrame()
                document.title shouldBe "Settings · ListenUp"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("the tab follows navigation, and Home is simply ListenUp") {
            val (host, router, composition) = mountAt("/settings")

            try {
                navItem(host, "Search").click()
                awaitFrame()
                document.title shouldBe "Search · ListenUp"

                router.navigate(Route(emptyList()))
                awaitFrame()
                document.title shouldBe "ListenUp"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("navigating lands keyboard focus on the new page's heading") {
            // Otherwise focus stays on the sidebar link, a screen reader announces nothing, and the
            // next Tab walks the whole sidebar again before reaching the page.
            val (host, router, composition) = mountAt("/")

            try {
                navItem(host, "Settings").click()

                val focused = awaitFocusOn(".shell-main h1")
                focused.textContent shouldBe "Settings"
                // Focusable by script, but not a Tab stop.
                focused.getAttribute("tabindex") shouldBe "-1"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("the first load leaves focus where the browser put it") {
            val (host, router, composition) = mountAt("/settings")

            try {
                repeat(SETTLE_FRAMES) { awaitFrame() }

                host.querySelector(".shell-main h1") shouldNotBe null
                document.activeElement?.matches(".shell-main h1") shouldBe false
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("an open dialog keeps focus through a navigation") {
            // A modal owns focus until it closes. Pulling focus out to a heading behind it would put
            // the reader somewhere `inert` says they cannot be.
            val dialog = document.createElement("dialog") as HTMLElement
            dialog.setAttribute("open", "")
            document.body!!.appendChild(dialog)
            val (_, router, composition) = mountAt("/")

            try {
                router.navigate(Route(listOf("settings")))
                repeat(SETTLE_FRAMES) { awaitFrame() }

                document.activeElement?.matches(".shell-main h1") shouldBe false
            } finally {
                dialog.remove()
                composition.dispose()
                router.dispose()
            }
        }
    })

private const val FOCUS_POLL_MS = 10L

/** Comfortably past the frame the heading is focused on, so a "not focused" is not just "not yet". */
private const val SETTLE_FRAMES = 6
