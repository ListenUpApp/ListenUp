package com.calypsan.listenup.web.features.hardcover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchedBook
import com.calypsan.listenup.client.presentation.hardcover.KeepOffRemoves
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.features.bookdetail.BookDetailPage
import com.calypsan.listenup.web.features.bookdetail.readyBook
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList

private val MATCH = HardcoverMatchedBook(427_578L, "Project Hail Mary", listOf("Andy Weir"), 2021, chosenByYou = true, hcEditionId = 9_001L)

private fun HTMLElement.button(label: String): HTMLButtonElement =
    querySelectorAll("button").asList().map { it as HTMLButtonElement }.first { it.textContent.orEmpty().trim() == label }

private fun HTMLElement.syncSwitch(): HTMLInputElement = querySelector("input.sw-in[role=switch]") as HTMLInputElement

/** Spec B5's Book Detail panel on web, in each of the states the approved canvas draws. */
class BookHardcoverPanelTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun mount(
            state: BookHardcoverUiState,
            onFindMatch: () -> Unit = {},
            onRemoveMatch: () -> Unit = {},
            onSetSynced: (Boolean) -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                BookHardcoverPanel(state, onFindMatch = onFindMatch, onRemoveMatch = onRemoveMatch, onSetSynced = onSetSynced)
            }

        test("every linked panel is headed by Sync with Hardcover, on") {
            val host = mount(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE))
            awaitFrame()

            host.textContent.orEmpty() shouldContain "Sync with Hardcover"
            host.syncSwitch().checked shouldBe true
        }

        test("a book kept off is one calm line under its switch, off, with no match actions") {
            val host = mount(BookHardcoverUiState.KeptOff())
            awaitFrame()

            host.querySelector("h2")!!.textContent shouldBe "Hardcover"
            host.syncSwitch().checked shouldBe false
            host.textContent.orEmpty() shouldContain "Kept off Hardcover — nothing about this book is shared or brought in."
            host.textContent.orEmpty() shouldNotContain "Change match"
        }

        test("while syncing it again the switch already reads on, and the kept-off line has gone") {
            val host = mount(BookHardcoverUiState.KeptOff(isResuming = true))
            awaitFrame()

            host.syncSwitch().checked shouldBe true
            host.textContent.orEmpty() shouldNotContain "Kept off Hardcover —"
        }

        test("switching it back on syncs it again, with no dialog") {
            val synced = mutableListOf<Boolean>()
            val host = mount(BookHardcoverUiState.KeptOff(), onSetSynced = { synced += it })
            awaitFrame()

            host.syncSwitch().click()
            awaitFrame()
            synced shouldBe listOf(true)
            host.querySelector("dialog[open]") shouldBe null
        }

        test("with nothing visible to lose, switching off keeps it off at once") {
            val synced = mutableListOf<Boolean>()
            val host = mount(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE), onSetSynced = { synced += it })
            awaitFrame()

            host.syncSwitch().click()
            awaitFrame()
            synced shouldBe listOf(false)
            host.querySelector("dialog[open]") shouldBe null
        }

        test("with Hardcover reads and a To Read entry it asks first, the switch staying on behind; Keep off keeps it off") {
            val synced = mutableListOf<Boolean>()
            val host =
                mount(
                    BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE, keepOffRemoves = KeepOffRemoves.READS_AND_TO_READ),
                    onSetSynced = { synced += it },
                )
            awaitFrame()

            host.syncSwitch().click()
            awaitFrame()
            synced shouldBe emptyList()
            host.syncSwitch().checked shouldBe true
            val dialog = host.querySelector("dialog[open]") as HTMLElement
            dialog.textContent.orEmpty() shouldContain "Keep this book off Hardcover?"
            dialog.textContent.orEmpty() shouldContain
                "Its Hardcover reads leave Readers here and it comes off your To Read shelf. Nothing on Hardcover changes."
            dialog.button("Keep off").click()
            awaitFrame()
            synced shouldBe listOf(false)
        }

        test("with only Hardcover reads to lose, the confirmation names only the reads") {
            val host = mount(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE, keepOffRemoves = KeepOffRemoves.READS))
            awaitFrame()

            host.syncSwitch().click()
            awaitFrame()
            val body = (host.querySelector("dialog[open]") as HTMLElement).textContent.orEmpty()
            body shouldContain "Its Hardcover reads leave Readers here. Nothing on Hardcover changes."
            body shouldNotContain "To Read"
        }

        test("Cancel leaves it syncing, and the switch reads on") {
            val synced = mutableListOf<Boolean>()
            val host =
                mount(
                    BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE, keepOffRemoves = KeepOffRemoves.TO_READ),
                    onSetSynced = { synced += it },
                )
            awaitFrame()

            host.syncSwitch().click()
            awaitFrame()
            val dialog = host.querySelector("dialog[open]") as HTMLElement
            dialog.textContent.orEmpty() shouldContain "It comes off your To Read shelf. Nothing on Hardcover changes."
            dialog.textContent.orEmpty() shouldNotContain "Readers"
            dialog.button("Cancel").click()
            awaitFrame()
            synced shouldBe emptyList()
            host.querySelector("dialog[open]") shouldBe null
            host.syncSwitch().checked shouldBe true
        }

        test("a switch whose save was refused reads on again when the state comes back") {
            var state: BookHardcoverUiState by mutableStateOf(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE))
            val host =
                mounts.mount {
                    BookHardcoverPanel(
                        state,
                        onFindMatch = {},
                        onRemoveMatch = {},
                        onSetSynced = { state = BookHardcoverUiState.KeptOff() },
                    )
                }
            awaitFrame()

            host.syncSwitch().click()
            awaitFrame()
            host.syncSwitch().checked shouldBe false
            state = BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE)
            awaitFrame()
            host.syncSwitch().checked shouldBe true
        }

        test("a book that needs a match offers the switch too") {
            val synced = mutableListOf<Boolean>()
            val host = mount(BookHardcoverUiState.NeedsMatch, onSetSynced = { synced += it })
            awaitFrame()

            host.syncSwitch().checked shouldBe true
            host.syncSwitch().click()
            awaitFrame()
            synced shouldBe listOf(false)
        }

        // Decision 1: every book has the switch while Hardcover is connected.
        test("a book never matched shows the switch alone, on, and switching it off asks nothing") {
            val synced = mutableListOf<Boolean>()
            val host = mount(BookHardcoverUiState.Unmatched, onSetSynced = { synced += it })
            awaitFrame()

            host.querySelector("h2")!!.textContent shouldBe "Hardcover"
            host.syncSwitch().checked shouldBe true
            host.querySelector(".hc-match-title") shouldBe null
            host.syncSwitch().click()
            awaitFrame()
            synced shouldBe listOf(false)
            host.querySelector("dialog[open]") shouldBe null
        }

        test("needs a match explains itself and opens Find on Hardcover") {
            var opens = 0
            val host = mount(BookHardcoverUiState.NeedsMatch, onFindMatch = { opens++ })
            awaitFrame()

            host.querySelector("h2")!!.textContent shouldBe "Hardcover"
            host.textContent.orEmpty() shouldContain "Needs a match"
            host.textContent.orEmpty() shouldContain "Pick the right book so your listening syncs."
            host.button("Find on Hardcover").click()
            opens shouldBe 1
        }

        test("a linked book shows its match, who chose it, where it stands, and both ways to change it") {
            var finds = 0
            var removes = 0
            val host =
                mount(
                    BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE),
                    onFindMatch = { finds++ },
                    onRemoveMatch = { removes++ },
                )
            awaitFrame()

            host.querySelector("h2")!!.textContent shouldBe "On Hardcover"
            val text = host.textContent.orEmpty()
            text shouldContain "Project Hail Mary"
            text shouldContain "Andy Weir · Audiobook · 2021"
            text shouldContain "Matched by you"
            host.querySelector("[role=status]")!!.textContent shouldBe "Up to date"
            host.button("Change match").click()
            host.button("Remove match").click()
            finds shouldBe 1
            removes shouldBe 1
        }

        test("each sync state has its own words") {
            mapOf(
                HardcoverBookSync.WAITING to "Updating…",
                HardcoverBookSync.NOTHING_SENT_YET to "Not sent yet",
                HardcoverBookSync.REMOVED_ON_HARDCOVER to "Stopped — you removed it on Hardcover",
            ).forEach { (sync, words) ->
                val host = mount(BookHardcoverUiState.Linked(MATCH.copy(chosenByYou = false), sync))
                awaitFrame()
                host.querySelector("[role=status]")!!.textContent shouldBe words
                host.textContent.orEmpty() shouldNotContain "Matched by you"
            }
        }

        test("a match made a moment ago reads Matched just now in place of its sync state") {
            val host = mount(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.NOTHING_SENT_YET, justMatched = true))
            awaitFrame()

            host.querySelector("[role=status]")!!.textContent shouldBe "Matched just now"
            host.textContent.orEmpty() shouldNotContain "Not sent yet"
        }

        test("a match Hardcover could not name still says it is matched") {
            val host = mount(BookHardcoverUiState.Linked(MATCH.copy(title = null), HardcoverBookSync.UP_TO_DATE))
            awaitFrame()

            host.textContent.orEmpty() shouldContain "Matched on Hardcover"
        }

        test("hidden draws nothing") {
            val host = mount(BookHardcoverUiState.Hidden)
            awaitFrame()

            host.textContent.orEmpty() shouldBe ""
        }

        test("on Book Detail it sits in the side rail, after Details and before Readers") {
            val host =
                mounts.mount {
                    BookDetailPage(
                        state = readyBook(),
                        tab = "overview",
                        onSelectTab = {},
                        onOpenLibrary = {},
                        onPlay = {},
                        onRetryConnection = {},
                        hardcover = BookHardcoverUiState.NeedsMatch,
                    )
                }
            awaitFrame()

            val headings = host.querySelectorAll(".bd-side h2").asList().map { it.textContent }
            headings.indexOf("Hardcover") shouldBe headings.indexOf("Details") + 1
        }
    })
