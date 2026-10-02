package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchedBook
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import com.calypsan.listenup.web.features.readers.reader
import com.calypsan.listenup.web.features.readers.readersData
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private val HELD_MATCH =
    HardcoverMatchedBook(427_578L, "The Institute", listOf("Stephen King"), 2019, chosenByYou = true, hcEditionId = 9_001L)

/** A ratings state that draws its panel: one listener's own rating is enough. */
private fun loadedRatings() =
    BookRatingsUiState.Ready(
        listeners = null,
        mine = ListenerRating(bookId = "42", userId = "me", halfStars = 8, note = null, ratedAtMs = 0L),
        external = null,
        breakdown = emptyList(),
        canRefresh = false,
        isRefreshingExternal = false,
    )

/**
 * A held book's page is triage-only (spec §8): the held panel with Edit and Release, no Play, no
 * actions menu, and Release asks first (§7) in the calm words.
 */
class BookDetailHeldTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        val held = readyBook().copy(isAdmin = true, isHeld = true, canPlay = false)

        fun rendered(
            state: BookDetailUiState,
            onRelease: () -> Unit = {},
            onEdit: () -> Unit = {},
            onMatch: () -> Unit = {},
            onEditChapters: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                BookDetailPage(
                    state = state,
                    tab = "overview",
                    onSelectTab = {},
                    onOpenLibrary = {},
                    onPlay = {},
                    onRetryConnection = {},
                    onEdit = onEdit,
                    onMatchMetadata = onMatch,
                    onEditChapters = onEditChapters,
                    onReleaseFromInbox = onRelease,
                )
            }

        fun buttons(host: HTMLElement): List<HTMLButtonElement> =
            host.querySelectorAll("button").asList().filterIsInstance<HTMLButtonElement>()

        fun button(
            host: HTMLElement,
            label: String,
        ): HTMLButtonElement? = buttons(host).firstOrNull { it.textContent?.trim() == label }

        test("a held book's page is the held panel, with no Play and no actions menu") {
            val host = rendered(held)

            host.querySelector(".bd-held").shouldNotBeNull()
            host.querySelector(".bd-held-t")?.textContent shouldBe "Held for review"
            host.querySelector(".bd-held-sub")?.textContent shouldBe
                "Hidden from all members. It can’t be played until you release it."
            // The hero row goes: Play, and the actions menu carrying shelf, collection and share.
            host.querySelector(".bd-actions").shouldBeNull()
            host.querySelector("[aria-haspopup='menu']").shouldBeNull()
            button(host, "Play").shouldBeNull()
            button(host, "Resume").shouldBeNull()
        }

        // Spec §8 and §10: Release, Edit, Match and Edit chapters — and nothing else.
        test("the held panel offers exactly the four triage actions") {
            val host = rendered(held)

            host.querySelectorAll(".bd-held-actions button").asList().map { it.textContent?.trim() } shouldBe
                listOf("Edit", "Release", "Match metadata", "Edit chapters")
        }

        test("Match and Edit chapters take the routes the hero row and the Chapters tab use") {
            var matches = 0
            var chapterEdits = 0
            val host = rendered(held, onMatch = { matches++ }, onEditChapters = { chapterEdits++ })

            button(host, "Match metadata").shouldNotBeNull().click()
            button(host, "Edit chapters").shouldNotBeNull().click()

            matches shouldBe 1
            chapterEdits shouldBe 1
        }

        // Two actions under one glyph read as one action twice. Android gives Edit chapters a
        // numbered list; so does this.
        test("Edit chapters has a glyph of its own, not Edit's pencil") {
            val host = rendered(held)

            val edit = button(host, "Edit").shouldNotBeNull().querySelector("svg")?.innerHTML
            val chapters = button(host, "Edit chapters").shouldNotBeNull().querySelector("svg")?.innerHTML
            chapters.shouldNotBeNull() shouldNotBe edit
        }

        test("the header marks it Held, beside the byline") {
            val host = rendered(held)

            host.querySelector(".bd-head .held-pill").shouldNotBeNull()
        }

        test("Release asks first, in the calm words, with Cancel first") {
            val host = rendered(held)

            button(host, "Release").shouldNotBeNull().click()
            awaitFrame()

            val dialog = host.querySelector("dialog.dlg").shouldNotBeNull()
            dialog.querySelector(".dlg-t")?.textContent shouldBe "Release to everyone?"
            dialog.querySelector(".dlg-p")?.textContent shouldBe "Every member will be able to find and play it."
            host.querySelectorAll("dialog.dlg .dlg-actions button").asList().map { it.textContent } shouldBe
                listOf("Cancel", "Release")
        }

        test("confirming releases; cancelling does not") {
            var releases = 0
            val host = rendered(held, onRelease = { releases++ })

            button(host, "Release").shouldNotBeNull().click()
            awaitFrame()
            (host.querySelectorAll("dialog.dlg .dlg-actions button").item(0) as HTMLElement).click()
            awaitFrame()
            releases shouldBe 0

            button(host, "Release").shouldNotBeNull().click()
            awaitFrame()
            (host.querySelectorAll("dialog.dlg .dlg-actions button").item(1) as HTMLElement).click()
            awaitFrame()
            releases shouldBe 1
        }

        test("Edit opens the editor") {
            var edits = 0
            val host = rendered(held, onEdit = { edits++ })

            button(host, "Edit").shouldNotBeNull().click()

            edits shouldBe 1
        }

        test("a release in flight says so, and no action can be pressed again") {
            val host = rendered(held.copy(isReleasingFromInbox = true))

            button(host, "Releasing…").shouldNotBeNull().disabled shouldBe true
            button(host, "Edit").shouldNotBeNull().disabled shouldBe true
            button(host, "Match metadata").shouldNotBeNull().disabled shouldBe true
            button(host, "Edit chapters").shouldNotBeNull().disabled shouldBe true
        }

        // Spec §9: ratings, Hardcover and readers are hidden for a held book, not refused. Every one
        // of them is supplied in a state that draws its panel, so only the guard can keep them out —
        // the ordinary book beside it proves the fixture really does draw them.
        test("a held book's side column is Details alone, even with ratings, a Hardcover match and readers") {
            fun sidePanels(state: BookDetailUiState): List<String?> {
                val host =
                    mounts.mount {
                        BookDetailPage(
                            state = state,
                            tab = "overview",
                            onSelectTab = {},
                            onOpenLibrary = {},
                            onPlay = {},
                            onRetryConnection = {},
                            ratings = loadedRatings(),
                            hardcover = BookHardcoverUiState.Linked(HELD_MATCH, HardcoverBookSync.UP_TO_DATE),
                            readers = readersData(reader(userId = "u1", progressPct = 40)),
                        )
                    }
                return host.querySelectorAll(".bd-side h2").asList().map { it.textContent?.trim() }
            }

            sidePanels(readyBook()) shouldBe listOf("Details", "On Hardcover", "Ratings", "Readers")
            sidePanels(held) shouldBe listOf("Details")
        }

        // The held panel holds the button that released the book, so a successful release unmounts
        // the very control that had focus. Without a hand-off focus falls to <body> and the next
        // Tab starts from the top of the document; the house pattern is the page's own H1.
        test("after a release the page's heading takes focus, not <body>") {
            var state by mutableStateOf<BookDetailUiState>(held)
            val host =
                mounts.mount {
                    BookDetailPage(
                        state = state,
                        tab = "overview",
                        onSelectTab = {},
                        onOpenLibrary = {},
                        onPlay = {},
                        onRetryConnection = {},
                        onReleaseFromInbox = { state = held.copy(isHeld = false, canPlay = true) },
                    )
                }

            button(host, "Release").shouldNotBeNull().focus()
            button(host, "Release").shouldNotBeNull().click()
            awaitFrame()
            (host.querySelectorAll("dialog.dlg .dlg-actions button").item(1) as HTMLElement).click()
            awaitFrame()
            awaitFrame()

            host.querySelector(".bd-held").shouldBeNull()
            document.activeElement shouldBe host.querySelector(".bd-head h1")
        }

        test("an ordinary book has no held panel and keeps its actions") {
            val host = rendered(readyBook())

            host.querySelector(".bd-held").shouldBeNull()
            host.querySelector(".bd-actions").shouldNotBeNull()
        }
    })
