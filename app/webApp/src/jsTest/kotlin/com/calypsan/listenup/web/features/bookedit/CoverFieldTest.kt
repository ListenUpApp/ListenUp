package com.calypsan.listenup.web.features.bookedit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
import kotlin.coroutines.resume

/** How long a spec waits for an event that SHOULD arrive. */
private const val EVENT_TIMEOUT_MS = 2_000L

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

/** A real 1x1 PNG, so an `<img>` can genuinely decode it: the liveness probe below needs a loadable image. */
private const val TINY_PNG_BASE64 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

/** The PNG, plus [trailingBytes] of padding a decoder ignores, so two previews differ without ceasing to be images. */
private fun tinyPng(trailingBytes: Int = 0): ByteArray {
    val decoded = window.atob(TINY_PNG_BASE64)
    return ByteArray(decoded.length + trailingBytes) { i -> if (i < decoded.length) decoded[i].code.toByte() else 0 }
}

/**
 * Whether [url] still names a decodable image. Loaded through an `<img>`, the way the app itself
 * shows a preview, so it is judged by `img-src` — a `fetch` here would be judged by `connect-src`,
 * which the page's policy keeps closed to `blob:`.
 */
private suspend fun loadsAsImage(url: String): Boolean =
    suspendCancellableCoroutine { continuation ->
        val probe = document.createElement("img") as HTMLImageElement
        probe.addEventListener("load", { continuation.resume(true) })
        probe.addEventListener("error", { continuation.resume(false) })
        probe.src = url
    }

/** Drops a valid PNG on the cover control — the drop that MUST reach [BookEditUiEvent.UploadCover]. */
private fun dropImage(root: HTMLElement) {
    val transfer = js("new DataTransfer()").unsafeCast<DataTransfer>()
    transfer.items.add(imageFile())
    (root.querySelector(".cover-pick") as HTMLElement)
        .dispatchEvent(DragEvent("drop", DragEventInit(dataTransfer = transfer, bubbles = true)))
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
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun coverField(
            state: BookEditUiState,
            onEvent: (BookEditUiEvent) -> Unit = {},
        ): HTMLElement = mounts.mount { CoverField(state = state, onEvent = onEvent) }

        test("no pending cover renders the book's current artwork, hash-busted") {
            val root = coverField(withCover())

            val img = root.querySelector(".cover-art img") as HTMLImageElement
            img.src shouldContain "/api/v1/books/b1/cover"
            img.src shouldContain "v=abc123"

            val pick = root.querySelector(".cover-pick") as HTMLElement
            pick.textContent!! shouldContain "Click to choose an image"
            pick.textContent!! shouldContain "drag and drop"
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

            val img = root.querySelector(".cover-art img") as HTMLImageElement
            img.src shouldStartWith "blob:"
        }

        test("a replaced preview releases its object URL") {
            var state by mutableStateOf(withCover().copy(pendingCoverData = tinyPng()))
            val root = mounts.mount { CoverField(state = state, onEvent = {}) }
            val firstUrl = (root.querySelector(".cover-art img") as HTMLImageElement).src

            state = state.copy(pendingCoverData = tinyPng(trailingBytes = 1))
            withTimeout(EVENT_TIMEOUT_MS) {
                while ((root.querySelector(".cover-art img") as HTMLImageElement).src == firstUrl) delay(10)
            }

            // Positive control: the CURRENT preview's URL must load — proving the failure below is
            // revocation, not an environment that can't load blob: images at all.
            loadsAsImage((root.querySelector(".cover-art img") as HTMLImageElement).src) shouldBe true

            // A revoked blob: URL no longer loads — that failure IS the assertion.
            loadsAsImage(firstUrl) shouldBe false
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
            dropImage(root)

            awaitFirstEvent(events)
            // Exactly one, and it is the PNG: the PDF drop before it contributed nothing.
            // A clock-based "nothing arrived in 150 ms" proves that only on a fast machine.
            events.single().shouldBeInstanceOf<BookEditUiEvent.UploadCover>().filename shouldBe "new-cover.png"
        }

        test("dropping while an upload is in flight is ignored") {
            // disabled suppresses CLICK, not the drag-and-drop machinery — the drop handler
            // must guard isUploadingCover itself.
            var state by mutableStateOf(withCover().copy(isUploadingCover = true))
            val events = mutableListOf<BookEditUiEvent>()
            val root = mounts.mount { CoverField(state = state, onEvent = { events += it }) }

            dropImage(root) // ignored: an upload is in flight
            state = state.copy(isUploadingCover = false)
            awaitFrame() // the recomposition that re-enables the field
            dropImage(root) // must be accepted

            awaitFirstEvent(events)
            // Exactly one: the drop that landed while uploading contributed nothing.
            events.single().shouldBeInstanceOf<BookEditUiEvent.UploadCover>()
        }
    })
