package com.calypsan.listenup.web

import com.calypsan.listenup.web.features.bookdetail.fixedBookDetail
import com.calypsan.listenup.web.features.bookdetail.readyBook
import com.calypsan.listenup.web.nav.Router
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.jetbrains.compose.web.renderComposable
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.calypsan.listenup.web.features.library.fakeLibrary
import com.calypsan.listenup.web.features.nowplaying.fixedPlayback

/**
 * The root wiring: the sidebar drives the URL and the URL drives the sidebar. This is where the
 * "URL is the contract" rule becomes observable behaviour rather than a codec property.
 */
class WebAppRootTest :
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
            renderComposable(root = host) { WebAppRoot(router, fixedBookDetail(readyBook()), fakeLibrary(), fixedPlayback()) }
            return host to router
        }

        test("the active sidebar item derives from the URL") {
            val (host, router) = mountAt("/library")

            try {
                val activeItem = host.querySelector(".nav-i.on") as HTMLElement
                activeItem.textContent.orEmpty() shouldContain "Library"
            } finally {
                router.dispose()
            }
        }

        test("the root URL is Home") {
            val (host, router) = mountAt("/")

            try {
                val activeItem = host.querySelector(".nav-i.on") as HTMLElement
                activeItem.textContent.orEmpty() shouldContain "Home"
            } finally {
                router.dispose()
            }
        }

        test("clicking a sidebar item rewrites the URL") {
            val (host, router) = mountAt("/")

            try {
                val items = host.querySelectorAll(".nav-i")
                (items.item(1) as HTMLElement).click()

                window.location.pathname shouldBe "/library"
                // Recomposition is frame-scheduled, so the re-rendered active item only exists
                // after the next frame.
                awaitFrame()
                (host.querySelector(".nav-i.on") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "Library"
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
