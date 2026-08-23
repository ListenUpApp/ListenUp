package com.calypsan.listenup.web

import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.web.features.bookedit.fixedBookEdit
import com.calypsan.listenup.web.features.bookdetail.fixedBookDetail
import com.calypsan.listenup.web.features.bookdetail.readyBook
import com.calypsan.listenup.web.nav.Router
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
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

        fun mountAt(
            path: String,
            isAdmin: Flow<Boolean> = flowOf(false),
        ): Pair<HTMLElement, Router> {
            window.history.replaceState(null, "", path)
            val router = Router()
            val host = document.createElement("div") as HTMLElement
            document.body!!.appendChild(host)
            renderComposable(root = host) {
                WebAppRoot(
                    router,
                    fixedBookDetail(readyBook()),
                    fixedBookEdit(BookEditUiState()),
                    fakeLibrary(),
                    fixedPlayback(),
                    observeIsAdmin = { isAdmin },
                )
            }
            return host to router
        }

        fun navLabels(host: HTMLElement): List<String> {
            val items = host.querySelectorAll(".nav-i")
            return (0 until items.length).map { (items.item(it) as HTMLElement).textContent.orEmpty() }
        }

        test("the Admin entry waits for proof of admin") {
            // The entry used to be hardcoded for everyone — a member saw an Admin item whose
            // every destination would refuse them. The sidebar renders it only once the
            // repository says so.
            val (host, router) = mountAt("/")

            try {
                val labels = navLabels(host)
                labels.none { it.contains("Admin") } shouldBe true
                labels.any { it.contains("Settings") } shouldBe true
            } finally {
                router.dispose()
            }
        }

        test("an admin gets the Admin entry") {
            val (host, router) = mountAt("/", isAdmin = flowOf(true))

            try {
                // collectAsState starts false; the flow flips it on the next recomposition.
                withTimeout(RECOMPOSE_TIMEOUT_MS) {
                    while (navLabels(host).none { it.contains("Admin") }) delay(10)
                }
            } finally {
                router.dispose()
            }
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

/** How long a spec waits for a state-flow value to reach the DOM. */
private const val RECOMPOSE_TIMEOUT_MS = 2_000L
