package com.calypsan.listenup.web.features.bookedit

import com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event

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
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun edited(
            state: BookEditUiState,
            onEvent: (BookEditUiEvent) -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                BookEditPage(state = state, onEvent = onEvent, onOpenLibrary = {}, onOpenBook = {})
            }

        // ⛔ UTC, not local. `addedAt` is a real scanner timestamp and Android reads it through
        // `rememberDatePickerState`, which treats millis as UTC. A local rendering names a different
        // day than Android for anything added near midnight.
        //
        // TWO instants because one cannot catch both directions: 02:00Z is the previous day for a
        // reader behind UTC, 23:30Z is the next day for a reader ahead of it, and a local rendering
        // fails whichever one matches the runner's offset. (A runner sitting exactly on UTC can
        // distinguish neither — that is a limit of asserting this from inside a browser, not a gap
        // in the contract.)
        test("an instant early in the UTC day renders that day, not the one before") {
            val root = edited(loaded().copy(addedAt = INSTANT_EARLY_IN_THE_UTC_DAY))

            (root.querySelector("#edit-added-at") as HTMLInputElement).value shouldBe "2021-03-04"
        }

        test("an instant late in the UTC day renders that day, not the one after") {
            val root = edited(loaded().copy(addedAt = INSTANT_LATE_IN_THE_UTC_DAY))

            (root.querySelector("#edit-added-at") as HTMLInputElement).value shouldBe "2021-03-04"
        }

        test("a single-digit month and day are zero-padded, or the input rejects the value") {
            val root = edited(loaded().copy(addedAt = INSTANT_EARLY_JANUARY))

            (root.querySelector("#edit-added-at") as HTMLInputElement).value shouldBe "2021-01-02"
        }

        test("a book with no recorded date shows an empty field rather than the epoch") {
            val root = edited(loaded().copy(addedAt = null))

            (root.querySelector("#edit-added-at") as HTMLInputElement).value shouldBe ""
        }

        test("picking a date reports UTC midnight on that day") {
            var reported: Long? = -1L
            val root =
                edited(loaded(), onEvent = { if (it is BookEditUiEvent.AddedAtChanged) reported = it.epochMillis })

            typeInto(root, "#edit-added-at", "2021-03-04")

            reported shouldBe UTC_MIDNIGHT_ON_THE_FOURTH
        }

        // ⛔ Null, not 0. Epoch 0 is 1 January 1970, which the form would then redisplay as a real
        // answer — so clearing the field would invent a date rather than remove one.
        test("clearing the date reports no date rather than the epoch") {
            var reported: Long? = -1L
            val root =
                edited(
                    loaded().copy(addedAt = INSTANT_LATE_IN_THE_UTC_DAY),
                    onEvent = { if (it is BookEditUiEvent.AddedAtChanged) reported = it.epochMillis },
                )

            typeInto(root, "#edit-added-at", "")

            reported shouldBe null
        }

        // The round trip is where a timezone slip would corrupt data silently: open the form, save
        // it unchanged, and the stored day must not move.
        test("rendering a date and reporting it back leaves the day unchanged") {
            var reported: Long? = null
            val shown =
                (edited(loaded().copy(addedAt = INSTANT_LATE_IN_THE_UTC_DAY)).querySelector("#edit-added-at") as HTMLInputElement)
                    .value
            val root = edited(loaded(), onEvent = { if (it is BookEditUiEvent.AddedAtChanged) reported = it.epochMillis })

            typeInto(root, "#edit-added-at", shown)

            reported shouldBe UTC_MIDNIGHT_ON_THE_FOURTH
        }

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

        test("the edit page offers the cover control") {
            val root = edited(loaded())

            (root.querySelector(".cover-field") != null) shouldBe true
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

/** Sets a field's value and fires the `input` this form listens for. */
private fun typeInto(
    root: HTMLElement,
    selector: String,
    text: String,
) {
    val input = root.querySelector(selector) as HTMLInputElement
    input.value = text
    input.dispatchEvent(Event("input", js("({bubbles:true})")))
}

/** 2021-03-04T02:00:00Z — a local reading behind UTC names the 3rd. */
private const val INSTANT_EARLY_IN_THE_UTC_DAY = 1_614_823_200_000L

/** 2021-03-04T23:30:00Z — a local reading ahead of UTC names the 5th. */
private const val INSTANT_LATE_IN_THE_UTC_DAY = 1_614_900_600_000L

/**
 * 2021-01-02T12:00:00Z — a single-digit month and day, which must zero-pad.
 *
 * Midday deliberately: at midnight this would also shift day under a local rendering, and the
 * padding spec would then fail for a timezone reason rather than a padding one.
 */
private const val INSTANT_EARLY_JANUARY = 1_609_588_800_000L

/** 2021-03-04T00:00:00Z — what picking "2021-03-04" must report. */
private const val UTC_MIDNIGHT_ON_THE_FOURTH = 1_614_816_000_000L
