package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.presentation.match.MatchReceiptUiState
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private val hosts = mutableListOf<HTMLElement>()

private class ReceiptRig(
    initial: MatchReceiptUiState,
    private val subject: ReceiptSubject = ReceiptSubject.Book,
) {
    val state = MutableStateFlow(initial)
    val calls = mutableListOf<String>()

    fun mount(): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        hosts += host
        renderComposable(root = host) {
            MatchReceiptRegion(
                state = state.collectAsState().value,
                onUndo = { calls += "undo" },
                onDismiss = { calls += "dismiss" },
                subject = subject,
            )
        }
        return host
    }
}

private fun HTMLElement.receipt(): HTMLElement = querySelector(".bmx-receipt") as HTMLElement

/**
 * The receipt on Book Detail after Apply (W-04): what changed in one sentence, See what changed and
 * Undo. Never timed — it stays until dismissed — announced, and focused when it arrives and when
 * Undo settles.
 */
class MatchReceiptRegionTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("the receipt says what changed, in a status region that takes focus") {
            val host = ReceiptRig(MatchReceiptUiState.Shown(receipt(), undoing = false, undoError = null)).mount()
            awaitFrame()
            awaitFrame()

            val region = host.receipt()
            region.getAttribute("role") shouldBe "status"
            region.querySelector(".bmx-receipt-t")?.textContent shouldBe
                "Changed 5 fields, cover from Hardcover, 16 chapter names"
            document.activeElement shouldBe region
        }

        test("See what changed lists every change with where it came from") {
            val host = ReceiptRig(MatchReceiptUiState.Shown(receipt(), undoing = false, undoError = null)).mount()
            awaitFrame()

            host.buttonNamed("See what changed").shouldNotBeNull().click()
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
        }

        test("Undo undoes, then the region says so and takes focus again") {
            val rig = ReceiptRig(MatchReceiptUiState.Shown(receipt(), undoing = false, undoError = null))
            val host = rig.mount()
            awaitFrame()

            host.buttonNamed("Undo").shouldNotBeNull().click()
            rig.state.value = MatchReceiptUiState.Shown(receipt(), undoing = true, undoError = null)
            awaitFrame()
            host.buttonNamed("Undoing…").shouldNotBeNull().getAttribute("aria-disabled") shouldBe "true"
            (document.activeElement as HTMLElement).blur()

            rig.state.value = MatchReceiptUiState.Undone
            awaitFrame()
            awaitFrame()

            rig.calls shouldContainExactly listOf("undo")
            host.receipt().querySelector(".bmx-receipt-t")?.textContent shouldBe
                "Match undone. Everything it changed is back."
            document.activeElement shouldBe host.receipt()
        }

        test("an expired match says it can no longer be undone") {
            val host = ReceiptRig(MatchReceiptUiState.Expired).mount()
            awaitFrame()

            host.receipt().querySelector(".bmx-receipt-t")?.textContent shouldBe
                "This book has changed since, so the match can't be undone."
        }

        test("a failed Undo says nothing was changed") {
            val host =
                ReceiptRig(
                    MatchReceiptUiState.Shown(receipt(), undoing = false, undoError = TransportError.NetworkUnavailable()),
                ).mount()
            awaitFrame()

            host.receipt().querySelector(".bmx-receipt-t")?.textContent shouldBe
                "No internet connection. Check your network. Nothing was changed."
        }

        test("a receipt that cannot be undone offers no Undo") {
            val host = ReceiptRig(MatchReceiptUiState.Shown(receipt(undoable = false), false, null)).mount()
            awaitFrame()

            host.buttonNamed("Undo").shouldBeNull()
        }

        test("Dismiss is the way it goes: nothing times it out") {
            val rig = ReceiptRig(MatchReceiptUiState.Shown(receipt(), undoing = false, undoError = null))
            val host = rig.mount()
            awaitFrame()

            host.buttonNamed("Dismiss").shouldNotBeNull().click()
            awaitFrame()

            rig.calls shouldContainExactly listOf("dismiss")
        }

        test("with no receipt the region is empty but present, so what lands in it is announced") {
            val host = ReceiptRig(MatchReceiptUiState.None).mount()
            awaitFrame()

            host.receipt().textContent shouldBe ""
        }

        // MARK: a person's receipt (W-07)

        test("a person's receipt names them and what changed, offers Undo, and has no See what changed") {
            val rig =
                ReceiptRig(
                    MatchReceiptUiState.Shown(personReceipt(), undoing = false, undoError = null),
                    ReceiptSubject.Person("Ray Porter"),
                )
            val host = rig.mount()
            awaitFrame()
            awaitFrame()

            host.receipt().querySelector(".bmx-receipt-t")?.textContent shouldBe
                "Changed photo and biography for Ray Porter"
            document.activeElement shouldBe host.receipt()
            host.buttonNamed("See what changed").shouldBeNull()
            host.buttonNamed("Undo").shouldNotBeNull().click()
            rig.calls shouldContainExactly listOf("undo")
        }

        test("a person's receipt says only the photo, or only the biography, when that is all that changed") {
            val photoOnly =
                ReceiptRig(
                    MatchReceiptUiState.Shown(personReceipt(biography = null), undoing = false, undoError = null),
                    ReceiptSubject.Person("Ray Porter"),
                ).mount()
            val bioOnly =
                ReceiptRig(
                    MatchReceiptUiState.Shown(personReceipt(photo = null), undoing = false, undoError = null),
                    ReceiptSubject.Person("Ray Porter"),
                ).mount()
            awaitFrame()

            photoOnly.receipt().querySelector(".bmx-receipt-t")?.textContent shouldBe "Changed photo for Ray Porter"
            bioOnly.receipt().querySelector(".bmx-receipt-t")?.textContent shouldBe "Changed biography for Ray Porter"
        }

        test("a person who changed since says their Undo has expired") {
            val host = ReceiptRig(MatchReceiptUiState.Expired, ReceiptSubject.Person("Ray Porter")).mount()
            awaitFrame()

            host.receipt().querySelector(".bmx-receipt-t")?.textContent shouldBe
                "Ray Porter has changed since, so the match can't be undone."
        }
    })
