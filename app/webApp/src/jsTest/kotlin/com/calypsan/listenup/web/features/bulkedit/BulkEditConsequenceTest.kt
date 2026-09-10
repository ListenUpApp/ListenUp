package com.calypsan.listenup.web.features.bulkedit

import com.calypsan.listenup.client.domain.bulkedit.BulkEdit
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditPreviewRow
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

internal fun editing(
    bookCount: Int = 40,
    requestedCount: Int = bookCount,
    edits: List<BulkEdit> = emptyList(),
    preview: List<BulkEditPreviewRow> = emptyList(),
    changedBookCount: Int = 0,
    isApplying: Boolean = false,
    sharedPublisher: String? = null,
    sharedPublishYear: Int? = null,
    sharedLanguage: String? = null,
) = BulkEditUiState.Editing(
    bookCount = bookCount,
    requestedCount = requestedCount,
    edits = edits,
    preview = preview,
    changedBookCount = changedBookCount,
    isApplying = isApplying,
    sharedPublisher = sharedPublisher,
    sharedPublishYear = sharedPublishYear,
    sharedLanguage = sharedLanguage,
)

private fun publisher(name: String = "Tor") = BulkEdit.SetPublisher(name)

/**
 * The sentences a bulk edit promises with.
 *
 * A bulk edit has no undo, so every one of these is load-bearing: they are what a reader checks
 * before pressing a button that writes to forty books at once. They are pure functions precisely so
 * they can be pinned here rather than only being seen.
 */
class BulkEditConsequenceTest :
    FunSpec({

        // MARK: an untouched field

        // ⛔ "Leave it and no book is written to" is the promise the whole screen rests on. A field
        // nobody touches is never written, and the placeholder is what the books already say.
        test("an untouched field reports what the books agree on, and promises to write nothing") {
            val state = editing(bookCount = 40, sharedPublisher = "Tor")

            val consequence = state.consequenceOf<BulkEdit.SetPublisher>("Tor")
            consequence.text shouldBe "All 40 books say Tor. Leave it and no book is written to."
            consequence.writes shouldBe false
        }

        test("an untouched field over one book says so in the singular") {
            val consequence = editing(bookCount = 1, sharedPublisher = "Tor").consequenceOf<BulkEdit.SetPublisher>("Tor")

            consequence.text shouldBe "This book says Tor. Leave it and no book is written to."
        }

        test("an untouched field over books that disagree says they disagree") {
            editing(bookCount = 40).consequenceOf<BulkEdit.SetPublisher>(null).text shouldBe
                "Differs across 40 books. Leave it and no book is written to."
            editing(bookCount = 1).consequenceOf<BulkEdit.SetPublisher>(null).text shouldBe
                "This book has no value here. Leave it and no book is written to."
        }

        // MARK: an armed field

        test("an armed field names how many books it will be written to") {
            val state =
                editing(
                    bookCount = 40,
                    edits = listOf(publisher()),
                    preview = listOf(BulkEditPreviewRow(publisher(), affectedCount = 12)),
                )

            val consequence = state.consequenceOf<BulkEdit.SetPublisher>("Tor")
            consequence.text shouldBe "Written to 12 of 40 books."
            consequence.writes shouldBe true
        }

        test("an armed field over one book says so without counting") {
            val state =
                editing(
                    bookCount = 1,
                    edits = listOf(publisher()),
                    preview = listOf(BulkEditPreviewRow(publisher(), affectedCount = 1)),
                )

            state.consequenceOf<BulkEdit.SetPublisher>(null).text shouldBe "Written to this book."
        }

        test("an armed field that would change exactly one book says one, not the plural") {
            val state =
                editing(
                    bookCount = 40,
                    edits = listOf(publisher()),
                    preview = listOf(BulkEditPreviewRow(publisher(), affectedCount = 1)),
                )

            state.consequenceOf<BulkEdit.SetPublisher>(null).text shouldBe "Written to 1 of 40 books."
        }

        // ⛔ Not "written to 0 books". A field that changes nothing is armed but harmless, and
        // saying so plainly is what stops a reader clearing it in a panic.
        test("an armed field that changes nothing says so, and does not claim to write") {
            val state =
                editing(
                    bookCount = 40,
                    edits = listOf(publisher()),
                    preview = listOf(BulkEditPreviewRow(publisher(), affectedCount = 0)),
                )

            val consequence = state.consequenceOf<BulkEdit.SetPublisher>("Tor")
            consequence.text shouldBe "Written to no books — they already say this."
            consequence.writes shouldBe false
        }

        // ⛔ The armed sentence must beat the agreed one. A field the reader has typed into is an
        // instruction, and reporting "leave it and nothing is written" over it would be a lie.
        test("an armed field never falls back to the agreed sentence") {
            val state =
                editing(
                    bookCount = 40,
                    sharedPublisher = "Tor",
                    edits = listOf(publisher("Gollancz")),
                    preview = listOf(BulkEditPreviewRow(publisher("Gollancz"), affectedCount = 40)),
                )

            state.consequenceOf<BulkEdit.SetPublisher>("Tor").text shouldContain "Written to"
        }

        test("each field reads only its own instruction") {
            val state =
                editing(
                    bookCount = 40,
                    edits = listOf(publisher()),
                    preview = listOf(BulkEditPreviewRow(publisher(), affectedCount = 12)),
                )

            // The language field is untouched even though the publisher is armed.
            state.consequenceOf<BulkEdit.SetLanguage>("en").writes shouldBe false
        }

        // MARK: the preview panel

        test("a row says how much of the selection it touches") {
            affectsText(affectedCount = 12, bookCount = 40) shouldBe "12 of 40 books change"
            affectsText(affectedCount = 0, bookCount = 40) shouldBe "No books change"
            affectsText(affectedCount = 1, bookCount = 1) shouldBe "This book changes"
        }

        // ⛔ The gap between twelve and forty is the part a reader assumes is a bug. Naming it is
        // the difference between a number and an explanation.
        test("a row names the books it leaves alone, and why") {
            leftAloneNote(publisher(), affectedCount = 12, bookCount = 40) shouldBe
                "28 already say Tor, so they are left alone."
            leftAloneNote(publisher(), affectedCount = 39, bookCount = 40) shouldBe
                "1 already says Tor, so it is left alone."
        }

        test("a row that changes everything explains nothing — there is nothing left to explain") {
            leftAloneNote(publisher(), affectedCount = 40, bookCount = 40).shouldBeNull()
        }

        // ⛔ "Fantasy, Grimdark, Space Opera already say…" is not a sentence, and half a sentence in
        // a destructive preview is worse than none.
        test("a collection instruction has no value to name, so it says nothing") {
            leftAloneNote(BulkEdit.AddTags(listOf("Grimdark")), affectedCount = 12, bookCount = 40).shouldBeNull()
        }

        test("every instruction can name itself") {
            listOf(
                BulkEdit.SetPublisher("Tor") to "Publisher",
                BulkEdit.SetPublishYear(2010) to "Publication year",
                BulkEdit.SetLanguage("en") to "Language",
                BulkEdit.AddTags(listOf("Grimdark")) to "Add tags",
                BulkEdit.AddMoods(listOf("Sweeping")) to "Add moods",
            ).forEach { (edit, label) -> labelOf(edit) shouldBe label }
        }

        test("the proportion bar is the count as a fraction, and never divides by nothing") {
            proportionOf(BulkEditPreviewRow(publisher(), affectedCount = 10), bookCount = 40) shouldBe 0.25f
            proportionOf(BulkEditPreviewRow(publisher(), affectedCount = 0), bookCount = 0) shouldBe 0f
        }

        // MARK: the button and the outcome

        // ⛔ The count is the promise, so it goes on the button. "Apply" says nothing about scale,
        // and scale is the only thing that makes this operation frightening.
        test("the button names the number it will change") {
            applyLabel(changedBookCount = 12, isApplying = false) shouldBe "Change 12 books"
            applyLabel(changedBookCount = 1, isApplying = false) shouldBe "Change 1 book"
            applyLabel(changedBookCount = 0, isApplying = false) shouldBe "Change"
            applyLabel(changedBookCount = 12, isApplying = true) shouldBe "Applying…"
        }

        test("a finished run reports what it changed") {
            appliedLabel(1) shouldBe "1 book updated"
            appliedLabel(12) shouldBe "12 books updated"
        }

        // ⛔ There is no rollback, so a failure that does not name how many books were already
        // committed leaves the reader unable to tell what state their library is in.
        test("a run that stopped partway says how far it got") {
            failedLabel(1) shouldContain "Stopped after 1 book"
            failedLabel(7) shouldContain "Stopped after 7 books"
        }

        // ⛔ A book deleted from another device between the grid and this screen drops out
        // silently. Editing thirty-nine after choosing forty without saying so is the kind of quiet
        // difference nobody forgives.
        test("books that could not be loaded are named, not swallowed") {
            notLoadedNote(editing(bookCount = 39, requestedCount = 40)).shouldNotBeNullAndContain("1 of the 40")
            notLoadedNote(editing(bookCount = 37, requestedCount = 40)).shouldNotBeNullAndContain("3 of the 40")
            notLoadedNote(editing(bookCount = 40, requestedCount = 40)).shouldBeNull()
        }
    })

private fun String?.shouldNotBeNullAndContain(fragment: String) {
    (this ?: "").shouldContain(fragment)
}
