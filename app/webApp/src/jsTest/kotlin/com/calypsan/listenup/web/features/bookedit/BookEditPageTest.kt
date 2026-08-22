package com.calypsan.listenup.web.features.bookedit

import com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event

private fun edited(
    state: BookEditUiState,
    onEvent: (BookEditUiEvent) -> Unit = {},
): HTMLElement {
    val root = document.createElement("div") as HTMLElement
    document.body?.appendChild(root)
    renderComposable(root = root) {
        BookEditPage(state = state, onEvent = onEvent, onOpenLibrary = {}, onOpenBook = {})
    }
    return root
}

private fun loaded(): BookEditUiState =
    BookEditUiState(
        isLoading = false,
        bookId = "b1",
        title = "The $100 Startup",
        sortTitle = "$100 Startup, The",
        subtitle = "Reinvent the Way You Make a Living",
        description = "A book about small businesses.",
        publisher = "Random House Audio",
        publishYear = "2012",
        language = "en",
        isbn = "9780307951526",
        asin = "B0089LOJTY",
        abridged = false,
    )

/**
 * The web's first editing surface, over the shared `BookEditViewModel`.
 *
 * What these pin is the two halves of a controlled form: the state reaches the inputs, and every
 * change leaves as the ViewModel's own event. A form that renders correctly but posts nothing is
 * the failure this class of screen actually has, and it looks identical to a working one.
 */
class BookEditPageTest :
    FunSpec({

        test("the book's metadata reaches the fields") {
            val root = edited(loaded())

            (root.querySelector("#edit-title") as HTMLInputElement).value shouldBe "The $100 Startup"
            (root.querySelector("#edit-subtitle") as HTMLInputElement).value shouldBe "Reinvent the Way You Make a Living"
            (root.querySelector("#edit-description") as HTMLTextAreaElement).value shouldBe "A book about small businesses."
            (root.querySelector("#edit-publisher") as HTMLInputElement).value shouldBe "Random House Audio"
            (root.querySelector("#edit-isbn") as HTMLInputElement).value shouldBe "9780307951526"
        }

        test("editing a field reports the ViewModel's own event") {
            val events = mutableListOf<BookEditUiEvent>()
            val root = edited(loaded()) { events += it }

            val title = root.querySelector("#edit-title") as HTMLInputElement
            title.value = "A Corrected Title"
            title.dispatchEvent(Event("input", js("({bubbles:true})")))

            events shouldContain BookEditUiEvent.TitleChanged("A Corrected Title")
        }

        test("the description posts its own event, not the title's") {
            // The fields are near-identical in shape, so a copy-paste that wires two of them to
            // one event renders perfectly and silently overwrites the wrong column on save.
            val events = mutableListOf<BookEditUiEvent>()
            val root = edited(loaded()) { events += it }

            val description = root.querySelector("#edit-description") as HTMLTextAreaElement
            description.value = "New synopsis."
            description.dispatchEvent(Event("input", js("({bubbles:true})")))

            events shouldContain BookEditUiEvent.DescriptionChanged("New synopsis.")
        }

        test("the language picker shows the book's language selected") {
            val root = edited(loaded())

            (root.querySelector("#edit-language") as HTMLSelectElement).value shouldBe "en"
        }

        test("a book with no recorded language selects nothing rather than guessing") {
            // Imported metadata frequently has no language, and defaulting it to English would be
            // the page inventing a fact about someone's book.
            val root = edited(loaded().copy(language = null))

            (root.querySelector("#edit-language") as HTMLSelectElement).value shouldBe ""
        }

        test("Save reports Save") {
            val events = mutableListOf<BookEditUiEvent>()
            val root = edited(loaded()) { events += it }

            root.querySelectorAll("button").let { buttons ->
                (0 until buttons.length)
                    .map { buttons.item(it) as HTMLElement }
                    .first { it.textContent == "Save" }
                    .click()
            }

            events shouldContain BookEditUiEvent.Save
        }

        test("a save in flight disables both actions and says so") {
            val root = edited(loaded().copy(isSaving = true))

            val buttons = root.querySelectorAll(".edit-actions button")
            (0 until buttons.length).forEach { index ->
                (buttons.item(index) as HTMLElement).hasAttribute("disabled") shouldBe true
            }
            root.textContent!! shouldContain "Saving…"
        }

        test("an error is shown with a way to dismiss it, and the form survives") {
            val events = mutableListOf<BookEditUiEvent>()
            val root = edited(loaded().copy(error = "Could not save this book.")) { events += it }

            root.textContent!! shouldContain "Could not save this book."
            // The fields are still there — the reader's edits are not thrown away with the error.
            (root.querySelector("#edit-title") as HTMLInputElement).value shouldBe "The $100 Startup"

            (root.querySelector(".edit-error button") as HTMLElement).click()
            events shouldContain BookEditUiEvent.DismissError
        }

        test("a loading edit page renders no form to type into") {
            // Rendering empty inputs while the book is still loading invites a reader to start
            // typing into fields that are about to be overwritten by the loaded values.
            val root = edited(BookEditUiState(isLoading = true))

            root.querySelectorAll("input").length shouldBe 0
            root.textContent!! shouldContain "Loading…"
        }
    })
