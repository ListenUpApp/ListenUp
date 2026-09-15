package com.calypsan.listenup.web.features.bulkedit

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.client.domain.bulkedit.BulkEdit
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditPreviewRow
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event

private val hosts = mutableListOf<HTMLElement>()

private fun noActions(
    onPublisher: (String) -> Unit = {},
    onYear: (Int?) -> Unit = {},
    onApply: () -> Unit = {},
    onLeave: () -> Unit = {},
    onContributors: (List<BookContributorInput>) -> Unit = {},
) = BulkEditActions(
    onSeriesQuery = {},
    onContributorQuery = {},
    onPublisher = onPublisher,
    onYear = onYear,
    onLanguage = {},
    onSeries = {},
    onContributors = onContributors,
    onGenres = {},
    onTags = {},
    onMoods = {},
    onApply = onApply,
    onLeave = onLeave,
)

private fun page(
    state: BulkEditUiState,
    actions: BulkEditActions = noActions(),
    notice: String? = null,
    contributorMatches: List<ContributorSearchResult> = emptyList(),
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        BulkEditPage(
            state = state,
            catalog =
                BulkEditCatalog(
                    genres = emptyList(),
                    tags = emptyList(),
                    moods = emptyList(),
                    seriesMatches = emptyList(),
                    contributorMatches = contributorMatches,
                ),
            actions = actions,
            notice = notice,
        )
    }
    return host
}

private fun button(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? =
    host
        .querySelectorAll("button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { it.textContent?.trim() == label }

private fun searchResult(name: String) = ContributorSearchResult(id = name, name = name, bookCount = 1)

/**
 * The whole relation field around the input with [inputId].
 *
 * `RelationField` puts the caller's id on the `<input>`, not on a wrapper, and the chips and result
 * rows are siblings of that input — so an unscoped `.rel-chip` would also match the series field
 * sitting above this one on the same page.
 */
private fun field(
    host: HTMLElement,
    inputId: String,
): HTMLElement = (host.querySelector("#$inputId") as HTMLElement).closest(".f-wrap") as HTMLElement

/** Result rows only render while the box has something in it, so a spec must type before it clicks. */
private fun search(
    host: HTMLElement,
    inputId: String,
    query: String,
) {
    val input = host.querySelector("#$inputId") as HTMLInputElement
    input.value = query
    input.dispatchEvent(Event("input", EventInit(bubbles = true)))
}

/** The same person credited twice — the case a name-only chip key collapses into one. */
private fun bothRolesForOnePerson() =
    BulkEdit.AddContributors(
        listOf(
            BookContributorInput(name = "Neil Gaiman", role = "author", position = 0),
            BookContributorInput(name = "Neil Gaiman", role = "narrator", position = 1),
        ),
    )

private fun fieldRows(host: HTMLElement) = host.querySelectorAll(".bke-field").asList().filterIsInstance<HTMLElement>()

/**
 * Editing many books at once.
 *
 * What these pin: a field you do not touch is never written and says so; a field you do touch says
 * how many books it will reach and looks different while it does; the panel names every instruction
 * rather than only counting it; and the button carries the number rather than the word "Apply".
 */
class BulkEditPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("a loading selection draws nothing it does not know yet") {
            val host = page(BulkEditUiState.Loading)

            host.querySelector(".bke-skel").shouldNotBeNull()
            fieldRows(host).size shouldBe 0
        }

        test("the header counts the books being edited, in the right number") {
            page(editing(bookCount = 40)).querySelector(".bke-t")?.textContent shouldBe "Edit 40 books"
            page(editing(bookCount = 1)).querySelector(".bke-t")?.textContent shouldBe "Edit 1 book"
        }

        // ⛔ A book deleted from another device drops out of the selection silently. Editing
        // thirty-nine after choosing forty without saying so is what nobody forgives.
        test("books that could not be loaded are named on screen") {
            val host = page(editing(bookCount = 39, requestedCount = 40))

            val warn = host.querySelector(".bke-warn").shouldNotBeNull()
            warn.getAttribute("role") shouldBe "status"
            warn.textContent.shouldNotBeNull() shouldContain "1 of the 40"
        }

        test("a full selection claims nothing about missing books") {
            page(editing(bookCount = 40, requestedCount = 40)).querySelector(".bke-warn").shouldBeNull()
        }

        // ⛔ The placeholder is what the books ALREADY say — never a value. A value in the box would
        // be an instruction, and this form writes every instruction it holds.
        test("an untouched publishing field shows the shared value as a placeholder, not a value") {
            val host = page(editing(bookCount = 40, sharedPublisher = "Tor"))

            val field = host.querySelector("#bke-publisher") as HTMLInputElement
            field.value shouldBe ""
            field.getAttribute("placeholder") shouldBe "Tor"
        }

        test("books that disagree get a placeholder saying so") {
            val host = page(editing(bookCount = 40, sharedPublisher = null))

            (host.querySelector("#bke-publisher") as HTMLInputElement).getAttribute("placeholder") shouldBe
                "Multiple values"
        }

        test("every publishing field carries its own sentence") {
            val host = page(editing(bookCount = 40, sharedPublisher = "Tor"))

            host
                .querySelectorAll(".bke-consequence")
                .asList()
                .first()
                .textContent shouldBe
                "All 40 books say Tor. Leave it and no book is written to."
        }

        test("typing into a field reports the change") {
            val typed = mutableListOf<String>()
            val host = page(editing(), actions = noActions(onPublisher = { typed += it }))

            val field = host.querySelector("#bke-publisher") as HTMLInputElement
            field.value = "Gollancz"
            field.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()

            typed shouldContainExactly listOf("Gollancz")
        }

        // ⛔ Armed and resting differ on more than one signal, because one signal can be missed.
        test("an armed field is marked, and a resting one is not") {
            val armed =
                page(
                    editing(
                        edits = listOf(BulkEdit.SetPublisher("Tor")),
                        preview = listOf(BulkEditPreviewRow(BulkEdit.SetPublisher("Tor"), affectedCount = 12)),
                    ),
                )

            fieldRows(armed).first().classList.contains("armed") shouldBe true
            fieldRows(page(editing())).first().classList.contains("armed") shouldBe false
        }

        // ⛔ The clear button is not a nicety. A stray keystroke arms an instruction over forty
        // books, and the only thing that disarms it is emptying the field.
        test("a field with something in it offers a way back, and an empty one does not") {
            val cleared = mutableListOf<String>()
            val host =
                page(
                    editing(edits = listOf(BulkEdit.SetPublisher("Tor"))),
                    actions = noActions(onPublisher = { cleared += it }),
                )

            val clear = host.querySelector("button[aria-label=\"Clear Publisher\"]") as HTMLButtonElement
            clear.click()
            awaitFrame()

            cleared shouldContainExactly listOf("")
            page(editing()).querySelector("button[aria-label=\"Clear Publisher\"]").shouldBeNull()
        }

        test("the year field clears to nothing rather than to zero") {
            val years = mutableListOf<Int?>()
            val host =
                page(
                    editing(edits = listOf(BulkEdit.SetPublishYear(2010))),
                    actions = noActions(onYear = { years += it }),
                )

            (host.querySelector("button[aria-label=\"Clear Publication year\"]") as HTMLButtonElement).click()
            awaitFrame()

            years shouldContainExactly listOf(null)
        }

        // ⛔ Named rather than blank: an empty panel is indistinguishable from a broken one, and it
        // is the first thing every user of this screen sees.
        test("the preview names its resting state rather than showing an empty panel") {
            val host = page(editing())

            host.querySelector(".bke-empty")?.textContent.shouldNotBeNull() shouldContain "Nothing to change yet"
            host.querySelectorAll(".bke-row").asList().size shouldBe 0
        }

        // ⛔ Three bare counts are honest and unusable: the instruction the reader wants to
        // reconsider is not identifiable among them.
        test("every instruction is named as well as counted") {
            val host =
                page(
                    editing(
                        bookCount = 40,
                        preview =
                            listOf(
                                BulkEditPreviewRow(BulkEdit.SetPublisher("Tor"), affectedCount = 12),
                                BulkEditPreviewRow(BulkEdit.AddTags(listOf("Grimdark")), affectedCount = 40),
                            ),
                    ),
                )

            host.querySelectorAll(".bke-row-l").asList().map { it.textContent } shouldContainExactly
                listOf("Publisher", "Add tags")
            host.querySelectorAll(".bke-row-c").asList().map { it.textContent } shouldContainExactly
                listOf("12 of 40 books change", "40 of 40 books change")
        }

        test("a row names the books it leaves alone") {
            val host =
                page(
                    editing(
                        bookCount = 40,
                        preview = listOf(BulkEditPreviewRow(BulkEdit.SetPublisher("Tor"), affectedCount = 12)),
                    ),
                )

            host.querySelector(".bke-row-note")?.textContent shouldBe "28 already say Tor, so they are left alone."
        }

        // ⛔ Dimmed, never hidden: a vanished row reads as a lost edit.
        test("a row that changes nothing is dimmed rather than removed") {
            val host =
                page(
                    editing(
                        bookCount = 40,
                        preview = listOf(BulkEditPreviewRow(BulkEdit.SetPublisher("Tor"), affectedCount = 0)),
                    ),
                )

            val rows = host.querySelectorAll(".bke-row").asList().filterIsInstance<HTMLElement>()
            rows.size shouldBe 1
            rows.single().classList.contains("off") shouldBe true
        }

        // The bar repeats the count in a form nobody has to count — and says nothing extra to a
        // screen reader, which has already heard the number beside it.
        test("the proportion bar is presentational and sized to the share") {
            val host =
                page(
                    editing(
                        bookCount = 40,
                        preview = listOf(BulkEditPreviewRow(BulkEdit.SetPublisher("Tor"), affectedCount = 10)),
                    ),
                )

            host.querySelector(".bke-bar")?.getAttribute("role") shouldBe "presentation"
            (host.querySelector(".bke-bar-fill") as HTMLElement).style.width shouldBe "25%"
        }

        // ⛔ The count is the promise, so it goes on the button.
        test("the button names the number it will change, and is refused when that is none") {
            val nothing = page(editing(changedBookCount = 0))
            button(nothing, "Change").shouldNotBeNull().hasAttribute("disabled") shouldBe true

            val twelve = page(editing(changedBookCount = 12))
            button(twelve, "Change 12 books").shouldNotBeNull().hasAttribute("disabled") shouldBe false
        }

        test("applying reports the press, and cannot be pressed twice") {
            val applied = mutableListOf<Unit>()
            val host = page(editing(changedBookCount = 12), actions = noActions(onApply = { applied += Unit }))

            button(host, "Change 12 books").shouldNotBeNull().click()
            awaitFrame()
            applied.size shouldBe 1

            button(page(editing(changedBookCount = 12, isApplying = true)), "Applying…")
                .shouldNotBeNull()
                .hasAttribute("disabled") shouldBe true
        }

        test("a notice is announced") {
            val host = page(editing(), notice = "Stopped after 7 books. The rest were not changed.")

            val alert = host.querySelector(".bke-notice").shouldNotBeNull()
            alert.getAttribute("role") shouldBe "alert"
            alert.textContent shouldBe "Stopped after 7 books. The rest were not changed."
        }

        // ⛔ Every relation section says it adds rather than replaces. A reader who thinks "add
        // genres" might clear the ones already there will not use this screen at all.
        test("the relation sections promise that adding never removes") {
            val host = page(editing())

            val text = host.textContent.orEmpty()
            text shouldContain "every book keeps the series and the people it already has"
            text shouldContain "every book keeps the genres, tags and moods it already has"
            text shouldContain "A field you don’t touch is never written"
        }

        // ⛔ The bug this replaces: every bulk-added person was written as an author, whatever they
        // actually did. Adding a narrator to forty books silently created forty author credits.
        test("a person is credited in the role the picker names, not always as author") {
            var credited = emptyList<BookContributorInput>()
            val host =
                page(
                    editing(),
                    actions = noActions(onContributors = { credited = it }),
                    contributorMatches = listOf(searchResult("Rosamund Pike")),
                )

            val select = host.querySelector("#bke-role") as HTMLSelectElement
            select.value = "narrator"
            select.dispatchEvent(Event("change", EventInit(bubbles = true)))
            search(host, "bke-contributors", "Rosamund")
            awaitFrame()
            (field(host, "bke-contributors").querySelector(".rel-result") as HTMLElement).click()
            awaitFrame()

            credited.map { it.name to it.role } shouldContainExactly listOf("Rosamund Pike" to "narrator")
        }

        test("the picker offers every role the contract defines, not the two the lists show") {
            val host = page(editing())

            val options = (host.querySelector("#bke-role") as HTMLSelectElement).querySelectorAll("option")
            options.length shouldBe ContributorRole.entries.size
        }

        test("with the picker untouched a person is still credited as author") {
            var credited = emptyList<BookContributorInput>()
            val host =
                page(
                    editing(),
                    actions = noActions(onContributors = { credited = it }),
                    contributorMatches = listOf(searchResult("Stephen King")),
                )

            search(host, "bke-contributors", "Stephen")
            awaitFrame()
            (field(host, "bke-contributors").querySelector(".rel-result") as HTMLElement).click()
            awaitFrame()

            credited.map { it.role } shouldContainExactly listOf("author")
        }

        test("a credit chip names the role, so two people are told apart by more than name") {
            val host =
                page(
                    editing(edits = listOf(bothRolesForOnePerson())),
                )

            val chips = field(host, "bke-contributors").querySelectorAll(".rel-chip").asList()
            chips.map {
                it.textContent
                    .orEmpty()
                    .trim()
                    .removeSuffix("×")
                    .trim()
            } shouldContainExactly
                listOf("Neil Gaiman · Author", "Neil Gaiman · Narrator")
        }

        // ⛔ Keyed on name alone, removing either of these removed both — the same person credited
        // twice is the ordinary case (author and narrator of their own memoir), not an edge one.
        test("removing one of a person's two credits keeps the other") {
            var credited = emptyList<BookContributorInput>()
            val host =
                page(
                    editing(edits = listOf(bothRolesForOnePerson())),
                    actions = noActions(onContributors = { credited = it }),
                )

            (
                field(host, "bke-contributors")
                    .querySelector("[aria-label=\"Remove Neil Gaiman · Author\"]") as HTMLElement
            ).click()
            awaitFrame()

            credited.map { it.role } shouldContainExactly listOf("narrator")
        }

        test("leaving reports it") {
            val left = mutableListOf<Unit>()
            val host = page(editing(), actions = noActions(onLeave = { left += Unit }))

            button(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()

            left.size shouldBe 1
        }
    })
