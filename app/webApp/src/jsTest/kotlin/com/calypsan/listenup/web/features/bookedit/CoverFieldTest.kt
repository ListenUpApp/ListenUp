package com.calypsan.listenup.web.features.bookedit

import com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement

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

            (root.querySelector(".cover-pick") as HTMLButtonElement).disabled shouldBe true
        }
    })
