package com.calypsan.listenup.web.features.hardcover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.api.dto.hardcover.HardcoverHistory
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverBookToMatch
import com.calypsan.listenup.client.presentation.hardcover.HardcoverCandidateRow
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchedBook
import com.calypsan.listenup.client.presentation.hardcover.HardcoverSearchState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverSyncStatus
import com.calypsan.listenup.client.presentation.hardcover.KeepOffRemoves
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBook
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksUiState
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList

private val CONNECTED = HardcoverSettingsUiState.Connected(username = "simon", since = 0L, isDisconnecting = false, lastSyncedAt = 1L)

private fun book(n: Int) = HardcoverBookToMatch("b$n", "Book number $n", "Author $n", null, null)

private val HAIL_MARY =
    HardcoverCandidateRow(427_578L, 9_001L, "Project Hail Mary", listOf("Andy Weir", "Ray Porter"), 2021, 8_107, true, true)
private val SUMMARY = HardcoverCandidateRow(1L, null, "Summary of Project Hail Mary", emptyList(), 2022, 0, false, false)

private val MATCH = HardcoverMatchedBook(427_578L, "Project Hail Mary", listOf("Andy Weir"), 2021, chosenByYou = true, hcEditionId = 9_001L)

private fun matchReady(linkingId: Long? = null) =
    HardcoverMatchUiState.Ready(
        "b1",
        "Project Hail Mary",
        "Andy Weir",
        null,
        null,
        "Project Hail Mary",
        HardcoverSearchState.Results(listOf(HAIL_MARY, SUMMARY)),
        null,
        linkingId,
        false,
    )

private fun HTMLElement.buttonStartingWith(words: String): HTMLButtonElement =
    querySelectorAll("button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .first {
            it.textContent
                .orEmpty()
                .trim()
                .startsWith(words)
        }

private fun active(): HTMLElement? = document.activeElement as? HTMLElement

/** The text a screen reader takes as a button's name when it has no `aria-label`: its own words. */
private fun HTMLElement.spokenName(): String {
    getAttribute("aria-label")?.let { return it }
    return textContent.orEmpty().trim()
}

/**
 * #1562 on web: where keyboard focus lands after every in-place action and dialog, what each control is
 * called, and what is announced — the Hardcover screens' accessibility, measured.
 */
class HardcoverAccessibilityTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest {
            mounts.disposeAll()
            (document.activeElement as? HTMLElement)?.blur()
        }

        fun hardcover(
            initial: HardcoverSettingsUiState,
            onSyncNow: () -> Unit = {},
            onFindMatch: (String) -> Unit = {},
        ): Pair<HTMLElement, (HardcoverSettingsUiState) -> Unit> {
            var state by mutableStateOf(initial)
            val host =
                mounts.mount {
                    HardcoverPage(
                        state = state,
                        onConnect = {},
                        onDisconnect = {},
                        onSyncNow = onSyncNow,
                        onSetShareMode = {},
                        onSendHistory = {},
                        onDismissHistory = {},
                        onFindMatch = onFindMatch,
                        onOpenKeptOff = {},
                        onOpenSettings = {},
                        nowMs = 2L,
                        copyText = { _, onResult -> onResult(true) },
                    )
                }
            return host to { next -> state = next }
        }

        context("M-W1: an in-place action keeps the keyboard where the reader is") {
            test("Sync now keeps focus while its sync runs, says it is unavailable, and ignores a second press") {
                var syncs = 0
                val (host, setState) = hardcover(CONNECTED, onSyncNow = { syncs++ })
                awaitFrame()
                val syncNow = host.buttonStartingWith("Sync now")
                syncNow.focus()
                syncNow.click()
                setState(CONNECTED.copy(sync = HardcoverSyncStatus.Syncing))
                awaitFrame()

                active() shouldBe syncNow
                syncNow.hasAttribute("disabled") shouldBe false
                syncNow.getAttribute("aria-disabled") shouldBe "true"
                syncNow.click()
                syncs shouldBe 1

                setState(CONNECTED.copy(lastSyncedAt = 2L))
                awaitFrame()
                active() shouldBe syncNow
            }

            test("Send N books lands on the sending line, then on what the send came to, then Dismiss on Sync now") {
                val (host, setState) = hardcover(CONNECTED.copy(history = HardcoverHistory.Offer(12)))
                awaitFrame()
                host.buttonStartingWith("Send 12 books").focus()

                setState(CONNECTED.copy(history = HardcoverHistory.Sending(3, 12)))
                awaitFrame()
                active().shouldNotBeNull().textContent.orEmpty() shouldStartWith "Sending 3 of 12 books"
                active()!!.getAttribute("role") shouldBe "status"

                setState(CONNECTED.copy(history = HardcoverHistory.Done(9, 3)))
                awaitFrame()
                active()
                    .shouldNotBeNull()
                    .textContent
                    .orEmpty()
                    .trim() shouldBe "Sent 9 books to Hardcover"

                host.querySelector("button[aria-label='Dismiss']").shouldNotBeNull().let { (it as HTMLElement).focus() }
                setState(CONNECTED.copy(history = HardcoverHistory.None))
                awaitFrame()
                active()
                    .shouldNotBeNull()
                    .textContent
                    .orEmpty()
                    .trim() shouldBe "Sync now"
            }

            test("Not now lands on the quiet row's Send, and that Send lands on the sending line") {
                val (host, setState) = hardcover(CONNECTED.copy(history = HardcoverHistory.Offer(12)))
                awaitFrame()
                host.buttonStartingWith("Not now").focus()

                setState(CONNECTED.copy(history = HardcoverHistory.Available(12)))
                awaitFrame()
                active().shouldNotBeNull().getAttribute("aria-label") shouldBe "Send earlier books to Hardcover"

                setState(CONNECTED.copy(history = HardcoverHistory.Sending(0, 12)))
                awaitFrame()
                active().shouldNotBeNull().getAttribute("role") shouldBe "status"
            }

            test("a page that arrives in a later phase does not take focus from wherever the reader is") {
                val (_, setState) = hardcover(CONNECTED)
                awaitFrame()

                setState(CONNECTED.copy(history = HardcoverHistory.Done(9, 0)))
                awaitFrame()

                (active() == null || active() == document.body) shouldBe true
            }

            test("Done's need-a-match puts focus on the Needs a match heading") {
                val (host, _) = hardcover(CONNECTED.copy(history = HardcoverHistory.Done(9, 1), booksToMatch = listOf(book(1))))
                awaitFrame()
                val link = host.buttonStartingWith("1 needs a match")
                link.focus()
                link.click()
                awaitFrame()

                active().shouldNotBeNull().tagName shouldBe "H2"
                active()!!.textContent.orEmpty() shouldStartWith "Needs a match"
            }
        }

        test("M-W2: closing Disconnect's question hands focus back to Disconnect") {
            val (host, _) = hardcover(CONNECTED)
            awaitFrame()
            val disconnect = host.buttonStartingWith("Disconnect")
            disconnect.focus()
            disconnect.click()
            awaitFrame()
            active().shouldNotBeNull().closest("dialog").shouldNotBeNull()

            (document.querySelector("dialog .dlg-actions button") as HTMLElement).click()
            awaitFrame()

            document.querySelector("dialog").shouldBeNull()
            active() shouldBe disconnect
        }

        test("m-W10: the Done status is the sentence alone, not the button beside it") {
            val (host, _) = hardcover(CONNECTED.copy(history = HardcoverHistory.Done(9, 3)))
            awaitFrame()

            val status = host.querySelector(".hc-history-done [role=status]").shouldNotBeNull()
            status.textContent.orEmpty().trim() shouldBe "Sent 9 books to Hardcover"
            status.querySelector("button").shouldBeNull()
        }

        test("m-W11: Copy code's result is said through a status region that was there before it") {
            val linking =
                HardcoverSettingsUiState.Linking("ABCD-1234", "https://hardcover.app/link", "https://hardcover.app/link?c=1", 0L)
            val (host, _) = hardcover(linking)
            awaitFrame()
            val region = host.querySelector(".hc-code-card [role=status]").shouldNotBeNull()
            region.textContent.orEmpty() shouldBe ""

            host.buttonStartingWith("Copy code").click()
            awaitFrame()

            host.querySelector(".hc-code-card [role=status]") shouldBe region
            region.textContent shouldBe "Code copied"
        }

        context("Needs a match") {
            test("m-W7: every Find on Hardcover is named for its own book, after its visible words") {
                val (host, _) = hardcover(CONNECTED.copy(booksToMatch = listOf(book(1), book(2)), isMatchListKnown = true))
                awaitFrame()

                val names =
                    host
                        .querySelectorAll(".hc-match-row .btn")
                        .asList()
                        .filterIsInstance<HTMLElement>()
                        .map { it.spokenName() }
                names shouldBe listOf("Find on Hardcover: Book number 1", "Find on Hardcover: Book number 2")
            }

            test("m-W6: the count is said inside the heading, and the drawn number is hidden from a screen reader") {
                val (host, _) = hardcover(CONNECTED.copy(booksToMatch = listOf(book(1), book(2), book(3)), isMatchListKnown = true))
                awaitFrame()

                val heading = host.querySelector("#hc-needs-match h2").shouldNotBeNull()
                heading.textContent shouldBe "Needs a match, 3 books"
                host
                    .querySelector(".hc-count")
                    .shouldNotBeNull()
                    .closest("[aria-hidden=true]")
                    .shouldNotBeNull()
            }

            test("m-W12: five books show, then Show all — which puts focus on the first book it revealed") {
                val books = (1..8).map(::book)
                val (host, _) = hardcover(CONNECTED.copy(booksToMatch = books, isMatchListKnown = true))
                awaitFrame()
                host.querySelectorAll(".hc-match-row").length shouldBe 5

                val showAll = host.buttonStartingWith("Show all 8 books")
                showAll.focus()
                showAll.click()
                awaitFrame()
                awaitFrame()

                host.querySelectorAll(".hc-match-row").length shouldBe 8
                active().shouldNotBeNull().spokenName() shouldBe "Find on Hardcover: Book number 6"
            }
        }

        test("m-W8: the breadcrumb is a navigation landmark whose last step is the current page") {
            val (host, _) = hardcover(CONNECTED)
            awaitFrame()

            val nav = host.querySelector("nav.crumb").shouldNotBeNull()
            nav.getAttribute("aria-label") shouldBe "Breadcrumb"
            nav.querySelectorAll("ol > li").length shouldBe 3
            nav.querySelector("[aria-current=page]").shouldNotBeNull().textContent shouldBe "Hardcover"
            nav.querySelectorAll(".sep").asList().all { (it as HTMLElement).getAttribute("aria-hidden") == "true" } shouldBe true
        }

        test("M-W3: the chosen share mode wears a check, and the other does not") {
            val (host, _) = hardcover(CONNECTED)
            awaitFrame()

            val options = host.querySelectorAll(".hc-share-mode .seg button").asList().filterIsInstance<HTMLElement>()
            options.map { it.getAttribute("aria-pressed") to (it.querySelector("svg") != null) } shouldBe
                listOf("true" to true, "false" to false)
        }

        test("the kept-off row's name is spaced: Kept off Hardcover, 2 books") {
            val (host, _) = hardcover(CONNECTED.copy(keptOffBookCount = 2))
            awaitFrame()

            host.querySelector(".hc-kept-row").shouldNotBeNull().textContent shouldBe "Kept off Hardcover, 2 books"
        }

        test("M-K2: each Sync again is named by its visible words, then its book; m-W6: the Books count is in the heading") {
            val host =
                mounts.mount {
                    KeptOffBooksPage(
                        state =
                            KeptOffBooksUiState.Loaded(
                                listOf(
                                    KeptOffBook("b1", "The Gate of the Feral Gods", "Matt Dinniman", null, null),
                                    KeptOffBook("b2", "Educated", "Tara Westover", null, null),
                                ),
                            ),
                        onSyncAgain = {},
                        onOpenSettings = {},
                        onOpenHardcover = {},
                    )
                }
            awaitFrame()

            val names =
                host
                    .querySelectorAll(".hc-match-row .btn")
                    .asList()
                    .filterIsInstance<HTMLElement>()
                    .map { it.spokenName() }
            names shouldBe listOf("Sync again: The Gate of the Feral Gods", "Sync again: Educated")
            host.querySelector("h2").shouldNotBeNull().textContent shouldBe "Books, 2 books"
        }

        context("Find on Hardcover") {
            fun matchPage(
                initial: HardcoverMatchUiState,
                onPick: (Long) -> Unit = {},
            ): Pair<HTMLElement, (HardcoverMatchUiState) -> Unit> {
                var state by mutableStateOf(initial)
                val host =
                    mounts.mount {
                        HardcoverMatchPage(
                            state = state,
                            onQueryChange = {},
                            onSearch = {},
                            onSearchFor = {},
                            onPick = onPick,
                            onRemoveMatch = {},
                            onOpenLibrary = {},
                            onOpenBook = {},
                        )
                    }
                return host to { next -> state = next }
            }

            test("M-C1, m-C3: each Pick is named Pick, then everything its row says") {
                val (host, _) = matchPage(matchReady())
                awaitFrame()

                val picks = host.querySelectorAll(".hc-result .btn").asList().filterIsInstance<HTMLElement>()
                picks.forEach { it.getAttribute("aria-label").shouldBeNull() }
                picks.map { it.spokenName() } shouldBe
                    listOf(
                        "Pick Project Hail Mary, Andy Weir, Ray Porter, Audiobook, 2021, 8.1k ratings",
                        "Pick Summary of Project Hail Mary, 2022, No ratings yet",
                    )
            }

            test("M-C2: while a pick saves, focus stays on it, and no Pick takes a press") {
                val picked = mutableListOf<Long>()
                val (host, setState) = matchPage(matchReady(), onPick = { picked += it })
                awaitFrame()
                val first = host.querySelector(".hc-result .btn") as HTMLButtonElement
                first.focus()
                first.click()
                setState(matchReady(linkingId = HAIL_MARY.hcBookId))
                awaitFrame()

                active() shouldBe first
                val picks = host.querySelectorAll(".hc-result .btn").asList().filterIsInstance<HTMLButtonElement>()
                picks.forEach {
                    it.disabled shouldBe false
                    it.getAttribute("aria-disabled") shouldBe "true"
                }
                picks[1].click()
                picked shouldBe listOf(HAIL_MARY.hcBookId)
            }
        }

        context("Book Detail's panel") {
            fun panel(initial: BookHardcoverUiState): Pair<HTMLElement, (BookHardcoverUiState) -> Unit> {
                var state by mutableStateOf(initial)
                val host = mounts.mount { BookHardcoverPanel(state = state, onFindMatch = {}, onRemoveMatch = {}, onSetSynced = {}) }
                return host to { next -> state = next }
            }

            test("M-W2: Cancel on Keep off hands focus back to the switch") {
                val (host, _) =
                    panel(
                        BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE, keepOffRemoves = KeepOffRemoves.READS),
                    )
                awaitFrame()
                val switch = host.querySelector(".sw-in") as HTMLInputElement
                switch.focus()
                switch.click()
                awaitFrame()

                (document.querySelector("dialog .dlg-actions button") as HTMLElement).click()
                awaitFrame()

                active() shouldBe switch
            }

            test("Keep off hands focus back to the switch, and it stays there when the book reads kept off") {
                val (host, setState) =
                    panel(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE, keepOffRemoves = KeepOffRemoves.READS))
                awaitFrame()
                val switch = host.querySelector(".sw-in") as HTMLInputElement
                switch.focus()
                switch.click()
                awaitFrame()

                (document.querySelectorAll("dialog .dlg-actions button").item(1) as HTMLElement).click()
                awaitFrame()
                setState(BookHardcoverUiState.KeptOff())
                awaitFrame()

                withClue("the switch is the same element, still focused, now off") {
                    active() shouldBe switch
                    switch.checked shouldBe false
                }
            }

            test("m-D2: a book never matched says so under its switch") {
                val (host, _) = panel(BookHardcoverUiState.Unmatched)
                awaitFrame()

                host.querySelector(".hc-kept-off").shouldNotBeNull().textContent shouldBe
                    "Not matched yet. ListenUp looks for it on Hardcover when you start listening."
            }
        }
    })
