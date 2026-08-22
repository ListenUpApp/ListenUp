package com.calypsan.listenup.web.features.bookedit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.web.renderComposable
import org.khronos.webgl.Int8Array
import org.w3c.dom.DataTransfer
import org.w3c.dom.DragEvent
import org.w3c.dom.DragEventInit
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.files.File
import org.w3c.files.FilePropertyBag

/** How long a spec waits for an event that SHOULD arrive. */
private const val EVENT_TIMEOUT_MS = 2_000L

/** How long a spec waits before declaring an event correctly did NOT arrive. */
private const val NO_EVENT_GRACE_MS = 150L

private fun coverField(
    state: BookEditUiState,
    onEvent: (BookEditUiEvent) -> Unit = {},
): HTMLElement {
    val root = document.createElement("div") as HTMLElement
    document.body?.appendChild(root)
    renderComposable(root = root) { CoverField(state = state, onEvent = onEvent) }
    return root
}

private fun withCover(): BookEditUiState =
    BookEditUiState(
        isLoading = false,
        bookId = "b1",
        title = "The $100 Startup",
        coverHash = "abc123",
    )

private fun imageFile(
    name: String = "new-cover.png",
    bytes: ByteArray = byteArrayOf(1, 2, 3),
    type: String = "image/png",
): File = File(arrayOf(bytes.unsafeCast<Int8Array>()), name, FilePropertyBag(type = type))

private suspend fun awaitFirstEvent(events: List<BookEditUiEvent>) {
    withTimeout(EVENT_TIMEOUT_MS) { while (events.isEmpty()) delay(10) }
}

/**
 * The cover control on Book Edit.
 *
 * What these pin: the current artwork renders from the server URL (hash-busted), a pending
 * replacement renders from its bytes, and both picking paths — the file input and a drop —
 * leave as the ViewModel's own UploadCover event.
 */
class CoverFieldTest :
    FunSpec({

        test("no pending cover renders the book's current artwork, hash-busted") {
            val root = coverField(withCover())

            val img = root.querySelector(".cover-pick img") as HTMLImageElement
            img.src shouldContain "/api/v1/books/b1/cover"
            img.src shouldContain "v=abc123"
        }

        test("an upload in flight disables the control") {
            val root = coverField(withCover().copy(isUploadingCover = true))

            val button = root.querySelector(".cover-pick") as HTMLButtonElement
            button.disabled shouldBe true
            button.getAttribute("aria-label") shouldBe "Change cover"
            button.getAttribute("type") shouldBe "button"
        }

        test("pending bytes render as a local preview, not a server fetch") {
            val root = coverField(withCover().copy(pendingCoverData = byteArrayOf(9, 9, 9)))

            val img = root.querySelector(".cover-pick img") as HTMLImageElement
            img.src shouldStartWith "blob:"
        }

        test("a replaced preview releases its object URL") {
            var state by mutableStateOf(withCover().copy(pendingCoverData = byteArrayOf(1)))
            val root = document.createElement("div") as HTMLElement
            document.body?.appendChild(root)
            renderComposable(root = root) { CoverField(state = state, onEvent = {}) }
            val firstUrl = (root.querySelector(".cover-pick img") as HTMLImageElement).src

            state = state.copy(pendingCoverData = byteArrayOf(2))
            withTimeout(EVENT_TIMEOUT_MS) {
                while ((root.querySelector(".cover-pick img") as HTMLImageElement).src == firstUrl) delay(10)
            }

            // Positive control: the CURRENT preview's URL must fetch fine — proving the
            // rejection below is revocation, not an environment that can't fetch blob: at all.
            window.fetch((root.querySelector(".cover-pick img") as HTMLImageElement).src).await()

            // A revoked blob: URL is unfetchable — that rejection IS the assertion.
            shouldThrowAny { window.fetch(firstUrl).await() }
        }

        test("choosing a file reports UploadCover with its bytes and name") {
            val events = mutableListOf<BookEditUiEvent>()
            val root = coverField(withCover()) { events += it }

            val input = root.querySelector("#edit-cover-input") as HTMLInputElement
            // DataTransfer has no public Kotlin constructor (external abstract class) — construct
            // the native object directly, same as any other browser-native test seam here.
            val transfer = js("new DataTransfer()").unsafeCast<DataTransfer>()
            transfer.items.add(imageFile())
            input.asDynamic().files = transfer.files
            input.dispatchEvent(Event("change", js("({bubbles:true})")))

            awaitFirstEvent(events)
            val upload = events.filterIsInstance<BookEditUiEvent.UploadCover>().single()
            upload.filename shouldBe "new-cover.png"
            upload.imageData.toList() shouldBe listOf<Byte>(1, 2, 3)
            input.value shouldBe ""
        }

        test("dropping an image routes the same upload path") {
            val events = mutableListOf<BookEditUiEvent>()
            val root = coverField(withCover()) { events += it }

            val transfer = js("new DataTransfer()").unsafeCast<DataTransfer>()
            transfer.items.add(imageFile())
            (root.querySelector(".cover-pick") as HTMLElement)
                .dispatchEvent(DragEvent("drop", DragEventInit(dataTransfer = transfer, bubbles = true)))

            awaitFirstEvent(events)
            events.filterIsInstance<BookEditUiEvent.UploadCover>().single().filename shouldBe "new-cover.png"
        }

        test("dropping a non-image is a no-op") {
            // The accept attribute filters the picker, but a drop bypasses it — the type check in
            // the drop handler is the only thing standing between a PDF and the cover slot.
            val events = mutableListOf<BookEditUiEvent>()
            val root = coverField(withCover()) { events += it }

            val transfer = js("new DataTransfer()").unsafeCast<DataTransfer>()
            transfer.items.add(imageFile(name = "not-a-cover.pdf", type = "application/pdf"))
            (root.querySelector(".cover-pick") as HTMLElement)
                .dispatchEvent(DragEvent("drop", DragEventInit(dataTransfer = transfer, bubbles = true)))

            delay(NO_EVENT_GRACE_MS)
            events.shouldBeEmpty()
        }

        test("dropping while an upload is in flight is ignored") {
            // disabled suppresses CLICK, not the drag-and-drop machinery — the drop handler
            // must guard isUploadingCover itself.
            val events = mutableListOf<BookEditUiEvent>()
            val root = coverField(withCover().copy(isUploadingCover = true)) { events += it }

            val transfer = js("new DataTransfer()").unsafeCast<DataTransfer>()
            transfer.items.add(imageFile())
            (root.querySelector(".cover-pick") as HTMLElement)
                .dispatchEvent(DragEvent("drop", DragEventInit(dataTransfer = transfer, bubbles = true)))

            delay(NO_EVENT_GRACE_MS)
            events.shouldBeEmpty()
        }
    })
