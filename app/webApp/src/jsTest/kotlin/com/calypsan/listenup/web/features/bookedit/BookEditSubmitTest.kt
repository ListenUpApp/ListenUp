package com.calypsan.listenup.web.features.bookedit

import com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.EventInit
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

private fun page(onEvent: (BookEditUiEvent) -> Unit): HTMLElement {
    val root = document.createElement("div") as HTMLElement
    document.body?.appendChild(root)
    renderComposable(root = root) {
        BookEditPage(
            state =
                BookEditUiState(
                    isLoading = false,
                    bookId = "b1",
                    title = "The Institute",
                ),
            onEvent = onEvent,
            onOpenLibrary = {},
            onOpenBook = {},
        )
    }
    return root
}

/**
 * Book Edit as a real form: Enter saves, and the two controls that must NOT save don't.
 *
 * See [com.calypsan.listenup.web.features.auth.EnterSubmitsTest] for why these use
 * `requestSubmit()` rather than a synthetic keypress.
 */
class BookEditSubmitTest :
    FunSpec({

        fun formOf(host: HTMLElement): HTMLFormElement {
            val form = host.querySelector("form")
            form shouldNotBe null
            return form as HTMLFormElement
        }

        test("Enter in a field saves the book") {
            val events = mutableListOf<BookEditUiEvent>()
            val host = page { events += it }

            formOf(host).asDynamic().requestSubmit()

            events shouldContain BookEditUiEvent.Save
        }

        test("saving never reloads the page and loses the edits") {
            val host = page { }

            val event = Event("submit", EventInit(bubbles = true, cancelable = true))
            formOf(host).dispatchEvent(event)

            event.defaultPrevented shouldBe true
        }

        test("Cancel is type=button, so it discards rather than saves") {
            // ⛔ An HTML <button> with no type defaults to SUBMIT. Inside a form that makes Cancel
            // save the very edits it exists to throw away — silently, and only once the page
            // became a real form.
            val host = page { }

            val cancel =
                (0 until host.querySelectorAll("button").length)
                    .map { host.querySelectorAll("button").item(it) as HTMLButtonElement }
                    .first { it.textContent == "Cancel" }

            cancel.type shouldBe "button"
        }

        test("Enter in a relation search box does not save the book") {
            // RelationField's own contract is that creating is never a side effect of Enter. Once
            // the page is a form, an unhandled Enter there submits it — turning a search box into
            // a save button.
            val events = mutableListOf<BookEditUiEvent>()
            val host = page { events += it }

            val genresLabel =
                (0 until host.querySelectorAll("label").length)
                    .map { host.querySelectorAll("label").item(it) as HTMLElement }
                    .first { it.textContent == "Genres" }
            val relationInput =
                host.querySelector("#" + genresLabel.getAttribute("for")) as HTMLInputElement
            val enter =
                KeyboardEvent(
                    "keydown",
                    KeyboardEventInit(key = "Enter", bubbles = true, cancelable = true),
                )
            relationInput.dispatchEvent(enter)

            enter.defaultPrevented shouldBe true
            events shouldNotContain BookEditUiEvent.Save
        }
    })
