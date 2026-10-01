package com.calypsan.listenup.web.features.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchedBook
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
import org.w3c.dom.asList

private val MATCH = HardcoverMatchedBook(427_578L, "Project Hail Mary", listOf("Andy Weir"), 2021, chosenByYou = true, hcEditionId = 9_001L)

private fun HTMLElement.button(label: String): HTMLButtonElement =
    querySelectorAll("button").asList().map { it as HTMLButtonElement }.first { it.textContent.orEmpty().trim() == label }

/** Spec B5's Book Detail panel on web, in each of the states the approved canvas draws. */
class BookHardcoverPanelTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun mount(
            state: BookHardcoverUiState,
            onFindMatch: () -> Unit = {},
            onRemoveMatch: () -> Unit = {},
        ): HTMLElement = mounts.mount { BookHardcoverPanel(state, onFindMatch = onFindMatch, onRemoveMatch = onRemoveMatch) }

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
