package com.calypsan.listenup.web.nav

import com.calypsan.listenup.web.RECOMPOSE_TIMEOUT_MS
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.mountAt
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLElement

/** Frames given to a scroll change to land: the shell applies it on the frame after the route moves. */
private const val SETTLE_FRAMES = 5

/** How far down the page the reader is before they navigate. */
private const val SCROLLED_TO = 600.0

/**
 * The shell has ONE scroll container that outlives every page, so nothing about a route change
 * moved it: open a book from deep in the library and you landed mid-page, and Back dropped you at
 * wherever the previous page happened to leave the offset.
 */
class ScrollRestorationTest :
    FunSpec({

        var originalUrl = ""

        beforeTest { originalUrl = window.location.pathname + window.location.search }

        afterTest { window.history.replaceState(null, "", originalUrl) }

        /** Makes the shell's scrollport tall enough to scroll, whatever the page inside it is. */
        fun HTMLElement.makeScrollable() {
            style.height = "300px"
            style.overflowY = "auto"
            // A spacer rather than padding: the box is border-box, and padding larger than the box
            // adds nothing to the scrollable height.
            val spacer = document.createElement("div") as HTMLElement
            spacer.style.height = "3000px"
            spacer.style.flexShrink = "0"
            appendChild(spacer)
        }

        suspend fun settle() = repeat(SETTLE_FRAMES) { awaitFrame() }

        test("a new page opens at the top") {
            val (host, router, composition) = mountAt("/")
            try {
                val main = host.querySelector(".shell-main") as HTMLElement
                main.makeScrollable()
                main.scrollTop = SCROLLED_TO
                main.scrollTop shouldBe SCROLLED_TO

                router.navigate(Route(listOf("settings")))
                settle()

                main.scrollTop shouldBe 0.0
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("Back returns to where you were") {
            val (host, router, composition) = mountAt("/")
            try {
                val main = host.querySelector(".shell-main") as HTMLElement
                main.makeScrollable()
                main.scrollTop = SCROLLED_TO
                router.navigate(Route(listOf("settings")))
                settle()
                main.scrollTop shouldBe 0.0

                window.history.back()
                withTimeout(RECOMPOSE_TIMEOUT_MS) {
                    while (router.current.segments.isNotEmpty()) awaitFrame()
                }
                settle()

                main.scrollTop shouldBe SCROLLED_TO
            } finally {
                composition.dispose()
                router.dispose()
            }
        }
    })
