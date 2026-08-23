package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.web.features.bookedit.fixedBookEdit
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.web.WebAppRoot
import com.calypsan.listenup.web.nav.Router
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.coroutines.flow.flowOf
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.calypsan.listenup.web.features.contributors.fixedContributors
import com.calypsan.listenup.web.features.library.fakeLibrary
import com.calypsan.listenup.web.features.nowplaying.fixedPlayback

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

        fun mountAt(
            path: String,
            source: OpenBookDetail = fixedBookDetail(readyBook()),
        ): Pair<HTMLElement, Router> {
            window.history.replaceState(null, "", path)
            val router = Router()
            val host = document.createElement("div") as HTMLElement
            document.body!!.appendChild(host)
            renderComposable(root = host) {
                WebAppRoot(
                    router,
                    source,
                    fixedBookEdit(BookEditUiState()),
                    fixedContributors(emptyList()),
                    fakeLibrary(),
                    fixedPlayback(),
                    observeIsAdmin = { flowOf(false) },
                )
            }
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

        test("the page asks the store for the book named in the URL") {
            var asked: String? = null
            val (_, router) =
                mountAt("/book/the-institute") { bookId ->
                    asked = bookId
                    fixedBookDetail(readyBook())(bookId)
                }

            try {
                asked shouldBe "the-institute"
            } finally {
                router.dispose()
            }
        }

        test("a book the store doesn't have says so, and still offers the way back") {
            // The honest state for this client today: no sync exists yet, so a deep link into an
            // empty browser store lands here. It must not look like a broken page.
            val (host, router) =
                mountAt("/book/42", fixedBookDetail(BookDetailUiState.Error(BookError.NotFound())))

            try {
                (host.querySelector(".bd .empty") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "Not in this browser's library"
                (host.querySelector(".bd .crumb a") != null) shouldBe true
                (host.querySelector(".bd-t") == null) shouldBe true
            } finally {
                router.dispose()
            }
        }

        test("a book still loading says that instead of showing an empty shell") {
            val (host, router) = mountAt("/book/42", fixedBookDetail(BookDetailUiState.Loading))

            try {
                (host.querySelector(".bd .empty") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "Loading"
            } finally {
                router.dispose()
            }
        }

        test("the header renders the book the store returned") {
            val (host, router) = mountAt("/book/42")

            try {
                (host.querySelector(".bd-t") as HTMLElement).textContent shouldBe "The Institute"
                (host.querySelector(".bd-by") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "Stephen King · read by Santino Fontana"
            } finally {
                router.dispose()
            }
        }

        test("a book with no chapter marks says so rather than drawing an empty table") {
            val (host, router) =
                mountAt(
                    "/book/42?tab=chapters",
                    fixedBookDetail(readyBook(chapters = emptyList())),
                )

            try {
                (host.querySelector(".chmap") == null) shouldBe true
                (host.querySelector(".bd .tblwrap") == null) shouldBe true
                (host.querySelector(".bd section") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "no chapter marks"
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
