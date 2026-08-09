package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.web.WebAppRoot
import com.calypsan.listenup.web.nav.Router
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Book Detail through the URL contract: `/book/{id}?tab=…` names the book and the pane, pane
 * switches replace rather than push (Back leaves the page, not the pane), and the breadcrumb is
 * a real navigation.
 */
class BookDetailTest :
    FunSpec({

        var originalUrl = ""

        beforeTest {
            originalUrl = window.location.pathname + window.location.search
        }

        afterTest {
            window.history.replaceState(null, "", originalUrl)
        }

        fun mountAt(path: String): Pair<HTMLElement, Router> {
            window.history.replaceState(null, "", path)
            val router = Router()
            val host = document.createElement("div") as HTMLElement
            document.body!!.appendChild(host)
            renderComposable(root = host) { WebAppRoot(router) }
            return host to router
        }

        test("a book deep link renders the detail page with Library active") {
            val (host, router) = mountAt("/book/42")

            try {
                (host.querySelector(".bd") != null) shouldBe true
                (host.querySelector(".nav-i.on") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "Library"
            } finally {
                router.dispose()
            }
        }

        test("the pane comes from the URL") {
            val (host, router) = mountAt("/book/42?tab=chapters")

            try {
                (host.querySelector(".tab.on") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "Chapters"
            } finally {
                router.dispose()
            }
        }

        test("switching panes rewrites the URL without growing history") {
            // A pane switch is shareable state, so it belongs in the URL — but Back should leave
            // the page, not unwind every pane the user looked at.
            val (host, router) = mountAt("/book/42")
            val depth = window.history.length

            try {
                val tabs = host.querySelectorAll(".tab")
                (tabs.item(1) as HTMLElement).click()

                window.location.search shouldContain "tab=chapters"
                window.history.length shouldBe depth
                awaitFrame()
                (host.querySelector(".tab.on") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "Chapters"
            } finally {
                router.dispose()
            }
        }

        test("the breadcrumb returns to the library") {
            val (host, router) = mountAt("/book/42")

            try {
                (host.querySelector(".crumb a") as HTMLElement).click()

                window.location.pathname shouldBe "/library"
                awaitFrame()
                (host.querySelector(".bd") == null) shouldBe true
            } finally {
                router.dispose()
            }
        }

        test("the overview pane lays out the book's details") {
            val (host, router) = mountAt("/book/42")

            try {
                host.querySelectorAll(".bd .meta-r").length shouldBeGreaterThanOrEqual 4
                (host.querySelector(".bd-side") != null) shouldBe true
                (host.querySelector(".bd-t") as HTMLElement).textContent.orEmpty() shouldContain "The Institute"
            } finally {
                router.dispose()
            }
        }
    })

/** Resolves after the next animation frame — when a scheduled recomposition has applied. */
private suspend fun awaitFrame() {
    suspendCoroutine { continuation ->
        window.requestAnimationFrame { window.requestAnimationFrame { continuation.resume(Unit) } }
    }
}
