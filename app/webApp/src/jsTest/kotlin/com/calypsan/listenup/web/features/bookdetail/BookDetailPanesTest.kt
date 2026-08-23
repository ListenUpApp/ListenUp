package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.web.features.bookedit.fixedBookEdit
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.web.WebAppRoot
import com.calypsan.listenup.web.nav.Router
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.coroutines.flow.flowOf
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import com.calypsan.listenup.web.features.contributordetail.fixedContributorDetail
import com.calypsan.listenup.web.features.contributors.fixedContributors
import com.calypsan.listenup.web.features.library.fakeLibrary
import com.calypsan.listenup.web.features.search.fixedSearch
import com.calypsan.listenup.client.presentation.search.SearchUiState
import com.calypsan.listenup.web.features.nowplaying.fixedPlayback

/**
 * The Files pane and the states with no book.
 *
 * Both are renders of what the contract already carries — `BookDetail.audioFiles` and the shared
 * `audioFormatDisplay` — rather than new capability. The absences are the other half of the
 * design: no Resume or Download (web has no playback, and its download enqueuer refuses by
 * design), no filesystem path (the contract carries `folderId`, not a per-book path), and no sync
 * button (web sync is unwritten). Each would be a control that cannot keep its promise, so each is
 * pinned absent here.
 */
class BookDetailPanesTest :
    FunSpec({

        var originalUrl = ""

        beforeTest { originalUrl = window.location.pathname + window.location.search }

        afterTest { window.history.replaceState(null, "", originalUrl) }

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
                    fixedContributorDetail(ContributorDetailUiState.Loading),
                    fixedContributors(emptyList()),
                    fakeLibrary(),
                    fixedSearch(SearchUiState.Idle()),
                    fixedPlayback(),
                    observeIsAdmin = { flowOf(false) },
                )
            }
            return host to router
        }

        fun tabText(host: HTMLElement): String =
            (0 until host.querySelectorAll(".tab").length)
                .mapNotNull { index -> (host.querySelectorAll(".tab").item(index) as? HTMLElement)?.textContent }
                .joinToString(" ")

        test("the page offers exactly the panes it has data for") {
            val (host, router) = mountAt("/book/42")

            try {
                val tabs = tabText(host)
                host.querySelectorAll(".tab").length shouldBe 3
                tabs shouldContain "Overview"
                tabs shouldContain "Chapters"
                tabs shouldContain "Files"
                // No activity source exists, and Readers needs a second ViewModel that isn't wired.
                tabs.contains("Activity") shouldBe false
            } finally {
                router.dispose()
            }
        }

        test("the files pane lists every file with the numbers an operator came to check") {
            val (host, router) = mountAt("/book/42?tab=files")

            try {
                host.querySelectorAll(".bd-main .tbl tbody tr").length shouldBe 3
                val main = (host.querySelector(".bd-main") as HTMLElement).textContent.orEmpty()
                main shouldContain "the-institute-part-1.m4b"
                main shouldContain "178 MB"
            } finally {
                router.dispose()
            }
        }

        test("the files rail reports the audio specs the primary file carries") {
            val (host, router) = mountAt("/book/42?tab=files")

            try {
                val rail = (host.querySelector(".bd-side") as HTMLElement).textContent.orEmpty()
                rail shouldContain "64 kbps"
                rail shouldContain "Files"
            } finally {
                router.dispose()
            }
        }

        test("a book whose files never got scanned says so rather than drawing an empty table") {
            val (host, router) =
                mountAt("/book/42?tab=files", fixedBookDetail(readyBook(audioFiles = emptyList())))

            try {
                (host.querySelector(".bd .tblwrap") == null) shouldBe true
                (host.querySelector(".bd") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "no audio files"
            } finally {
                router.dispose()
            }
        }

        test("a missing book offers the way back rather than only naming the problem") {
            val (host, router) =
                mountAt("/book/99", fixedBookDetail(BookDetailUiState.Error(BookError.NotFound())))

            try {
                val empty = host.querySelector(".bd .empty") as HTMLElement
                (empty.querySelector(".ico") != null) shouldBe true
                empty.textContent.orEmpty() shouldContain "Not in this browser's library"
                (empty.querySelector("button") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "Back to Library"
            } finally {
                router.dispose()
            }
        }

        test("the way back out of a missing book actually goes to the library") {
            val (host, router) =
                mountAt("/book/99", fixedBookDetail(BookDetailUiState.Error(BookError.NotFound())))

            try {
                (host.querySelector(".bd .empty button") as HTMLElement).click()

                window.location.pathname shouldBe "/library"
            } finally {
                router.dispose()
            }
        }

        test("no control is offered for a capability this client doesn't have") {
            val (host, router) = mountAt("/book/42")

            try {
                val page = (host.querySelector(".bd") as HTMLElement).textContent.orEmpty()
                page.contains("Resume") shouldBe false
                page.contains("Download") shouldBe false
            } finally {
                router.dispose()
            }
        }
    })
