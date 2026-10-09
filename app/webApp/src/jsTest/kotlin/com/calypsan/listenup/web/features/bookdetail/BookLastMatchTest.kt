package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.LastMatchEvent
import com.calypsan.listenup.client.presentation.bookdetail.LastMatchUi
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.features.match.buttonNamed
import com.calypsan.listenup.web.features.match.receipt
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private val hosts = mutableListOf<HTMLElement>()

private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val NOW_MS = 1_800_000_000_000L

private class LastMatchRig(
    initial: LastMatchUi?,
) {
    val lastMatch = MutableStateFlow(initial)
    val events = Channel<LastMatchEvent>(Channel.BUFFERED)
    val calls = mutableListOf<String>()

    fun mount(): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        hosts += host
        val session =
            fixedBookDetail(
                state = BookDetailUiState.Loading,
                lastMatch = lastMatch,
                lastMatchEvents = events.receiveAsFlow(),
                onSeeWhatChanged = { calls += "see" },
                onCloseWhatChanged = { calls += "close" },
                onUndoLastMatch = { calls += "undo" },
            )("book-1")
        renderComposable(root = host) { BookLastMatch(session = session, nowMs = NOW_MS) }
        return host
    }
}

private fun row(
    appliedAtMs: Long = NOW_MS - 3 * DAY_MS,
    matchedBy: String? = null,
) = LastMatchUi(
    receipt = receipt(),
    appliedAtMs = appliedAtMs,
    matchedBy = matchedBy,
    showingChanges = false,
    undoing = false,
    undoError = null,
)

private fun HTMLElement.lastMatch(): HTMLElement? = querySelector(".bmx-last") as? HTMLElement

/**
 * Book Detail's last-match row on the web: when the book was matched and by whom, See what changed and Undo last
 * match wired to the ViewModel, and Undo's outcome in the receipt's own status region.
 */
class BookLastMatchTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("the row says when the book was matched and offers See what changed and Undo last match") {
            val rig = LastMatchRig(row())
            val host = rig.mount()
            awaitFrame()

            host
                .lastMatch()
                .shouldNotBeNull()
                .querySelector(".bmx-last-t")
                ?.textContent shouldBe
                "Details matched 3 days ago"
            host.buttonNamed("See what changed").shouldNotBeNull().click()
            host.buttonNamed("Undo last match").shouldNotBeNull().click()

            rig.calls shouldContainExactly listOf("see", "undo")
        }

        test("a match made just now, by someone else, says so and names them") {
            val host = LastMatchRig(row(appliedAtMs = NOW_MS, matchedBy = "Sam")).mount()
            awaitFrame()

            host
                .lastMatch()
                .shouldNotBeNull()
                .querySelector(".bmx-last-t")
                ?.textContent shouldBe
                "Details matched just now by Sam"
        }

        test("See what changed opens the receipt's own list of changes, and Done closes it through the ViewModel") {
            val rig = LastMatchRig(row().copy(showingChanges = true))
            rig.mount()
            awaitFrame()

            val dialog = document.querySelector("dialog[open]").shouldNotBeNull()
            dialog.querySelectorAll(".bmx-changes li").asList().map { it.textContent } shouldContainExactly
                listOf(
                    "Description · from Audible",
                    "Cover · from Hardcover",
                    "Genres added: Space Opera",
                    "16 chapter names · from Audible",
                )
            (dialog.querySelector("button.btn-primary") as HTMLElement).click()
            awaitFrame()

            rig.calls shouldContainExactly listOf("close")
            rig.lastMatch.value = row()
            awaitFrame()
        }

        test("while undoing, Undo says so and holds its focus") {
            val host = LastMatchRig(row().copy(undoing = true)).mount()
            awaitFrame()

            host.buttonNamed("Undoing…").shouldNotBeNull().getAttribute("aria-disabled") shouldBe "true"
        }

        test("a failed Undo says why, and the row stays to try again") {
            val host = LastMatchRig(row().copy(undoError = TransportError.NetworkUnavailable())).mount()
            awaitFrame()

            host
                .lastMatch()
                .shouldNotBeNull()
                .querySelector(".bmx-last-err")
                ?.textContent shouldBe
                "No internet connection. Check your network. Nothing was changed."
            host.buttonNamed("Undo last match").shouldNotBeNull()
        }

        test("once undone, the receipt's region takes the row's place, says so, and takes focus") {
            val rig = LastMatchRig(row())
            val host = rig.mount()
            awaitFrame()

            rig.lastMatch.value = null
            rig.events.send(LastMatchEvent.Undone)
            awaitFrame()
            awaitFrame()

            host.lastMatch().shouldBeNull()
            val region = host.querySelector(".bmx-receipt").shouldNotBeNull() as HTMLElement
            region.querySelector(".bmx-receipt-t")?.textContent shouldBe "Match undone. Everything it changed is back."
            document.activeElement shouldBe region

            host.buttonNamed("Dismiss").shouldNotBeNull().click()
            awaitFrame()
            host.querySelector(".bmx-receipt-t").shouldBeNull()
        }

        test("an Undo the server calls too late says the match can no longer be undone") {
            val rig = LastMatchRig(row())
            val host = rig.mount()
            awaitFrame()

            rig.lastMatch.value = null
            rig.events.send(LastMatchEvent.Expired)
            awaitFrame()

            host.querySelector(".bmx-receipt-t")?.textContent shouldBe
                "This book has changed since, so the match can't be undone."
        }

        test("a book with no match to undo shows no row") {
            val host = LastMatchRig(null).mount()
            awaitFrame()

            host.lastMatch().shouldBeNull()
        }
    })
