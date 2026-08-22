package com.calypsan.listenup.web.features.bookedit

import com.calypsan.listenup.client.domain.model.EditableCollection
import com.calypsan.listenup.client.domain.model.EditableGenre
import com.calypsan.listenup.client.domain.model.EditableTag
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
import org.w3c.dom.events.Event

private fun page(
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

private fun ready(): BookEditUiState = BookEditUiState(isLoading = false, bookId = "b1", title = "Dune")

/**
 * The relational half of the edit form: four relations over one control.
 *
 * The assertions that matter are the ones about *which* relation a click reached, and about the
 * separation between choosing and inventing — a form that attaches the right label to the wrong
 * relation looks entirely correct on screen.
 */
class ClassificationFieldsTest :
    FunSpec({

        test("attached genres render as removable chips") {
            val root = page(ready().copy(genres = listOf(EditableGenre("g1", "Science Fiction", "/sf"))))

            root.textContent!! shouldContain "Science Fiction"
            root.querySelector(".rel-chip .rel-x")!!.getAttribute("aria-label") shouldBe "Remove Science Fiction"
        }

        test("removing a chip reports the relation it belongs to, not just its label") {
            val genre = EditableGenre("g1", "Science Fiction", "/sf")
            val events = mutableListOf<BookEditUiEvent>()
            val root = page(ready().copy(genres = listOf(genre))) { events += it }

            (root.querySelector(".rel-chip .rel-x") as HTMLElement).click()

            events shouldContain BookEditUiEvent.RemoveGenre(genre)
        }

        test("typing in the tag box searches tags, not genres") {
            // Four near-identical controls on one page: wiring two of them to the same event is the
            // realistic mistake, and it is invisible until something saves to the wrong column.
            val events = mutableListOf<BookEditUiEvent>()
            val root = page(ready()) { events += it }

            val tags = root.querySelector("#edit-tags") as HTMLInputElement
            tags.value = "cosy"
            tags.dispatchEvent(Event("input", js("({bubbles:true})")))

            events shouldContain BookEditUiEvent.TagSearchQueryChanged("cosy")
        }

        test("a tag with no match offers to create it") {
            val root = page(ready().copy(tagSearchQuery = "slow-burn", tagSearchResults = emptyList()))

            root.querySelector("#edit-tags")!!.let { }
            root.textContent!! shouldContain "Create \"slow-burn\""
        }

        test("creating a tag is a deliberate press, and reports the typed name") {
            val events = mutableListOf<BookEditUiEvent>()
            val root = page(ready().copy(tagSearchQuery = "slow-burn")) { events += it }

            (root.querySelector(".rel-create") as HTMLElement).click()

            events shouldContain BookEditUiEvent.TagEntered("slow-burn")
        }

        test("a genre with no match cannot be created, because genres are system-controlled") {
            val root = page(ready().copy(genreSearchQuery = "Sci-Fi-ish", genreSearchResults = emptyList()))

            root.querySelectorAll(".rel-create").length shouldBe 0
            root.textContent!! shouldContain "No matches."
        }

        test("collections are hidden from a reader who may not attach one") {
            val root = page(ready().copy(isAdmin = false, collections = listOf(EditableCollection("c1", "Staff Picks"))))

            root.querySelectorAll("#edit-collections").length shouldBe 0
        }

        test("collections appear for an admin") {
            val root = page(ready().copy(isAdmin = true))

            root.querySelectorAll("#edit-collections").length shouldBe 1
        }

        test("a search still running says so rather than claiming no matches") {
            val root = page(ready().copy(tagSearchQuery = "co", tagSearchLoading = true))

            root.textContent!! shouldContain "Searching…"
        }

        test("a tag's slug is title-cased on its chip") {
            val root = page(ready().copy(tags = listOf(EditableTag("t1", "found-family"))))

            root.querySelector(".rel-chip")!!.textContent!! shouldContain "Found Family"
        }
    })
