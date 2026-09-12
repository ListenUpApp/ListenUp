package com.calypsan.listenup.web.features.bookedit

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.client.domain.model.EditableContributor
import com.calypsan.listenup.client.domain.model.EditableSeries
import com.calypsan.listenup.client.domain.model.SeriesSearchResult
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import org.w3c.dom.asList

private fun ready(): BookEditUiState = BookEditUiState(isLoading = false, bookId = "b1", title = "Dune")

/**
 * Contributors and series — the two relations that are not just a chip.
 *
 * Both are keyed by NAME, because a just-typed contributor or series has no id until Save. Keying
 * on the nullable id instead makes a freshly-added author impossible to remove again, which is the
 * bug these pin.
 */
class PeopleAndSeriesTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun page(
            state: BookEditUiState,
            onEvent: (BookEditUiEvent) -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                BookEditPage(state = state, onEvent = onEvent, onOpenLibrary = {}, onOpenBook = {})
            }

        test("only roles in use get a section") {
            val root =
                page(
                    ready().copy(
                        visibleRoles = setOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR),
                    ),
                )

            root.querySelectorAll("#edit-role-author").length shouldBe 1
            root.querySelectorAll("#edit-role-narrator").length shouldBe 1
            root.querySelectorAll("#edit-role-illustrator").length shouldBe 0
        }

        test("the placeholder gets its article right for vowel-initial roles") {
            val root = page(ready().copy(visibleRoles = setOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR)))

            val placeholders =
                (0 until root.querySelectorAll("input").length)
                    .mapNotNull { (root.querySelectorAll("input").item(it) as HTMLElement).getAttribute("placeholder") }

            placeholders shouldContain "Add an author…"
            placeholders shouldContain "Add a narrator…"
        }

        test("a role not in use is offered as something to add") {
            val root = page(ready().copy(visibleRoles = setOf(ContributorRole.AUTHOR)))

            root.textContent!! shouldContain "+ Illustrator"
        }

        test("opening a hidden role reports which role") {
            val events = mutableListOf<BookEditUiEvent>()
            val root = page(ready().copy(visibleRoles = setOf(ContributorRole.AUTHOR))) { events += it }

            val add =
                root.querySelectorAll(".rel-add-role").let { buttons ->
                    (0 until buttons.length)
                        .map { buttons.item(it) as HTMLElement }
                        .first { it.textContent == "+ Narrator" }
                }
            add.click()

            events shouldContain BookEditUiEvent.AddRoleSection(ContributorRole.NARRATOR)
        }

        test("a contributor added but not yet saved can still be removed") {
            // ⛔ The id is null until Save. Keying the chip on it would make this contributor
            // unremovable — you could add a typo and have no way to take it back out.
            val fresh = EditableContributor(id = null, name = "Frank Herbert", roles = setOf(ContributorRole.AUTHOR))
            val events = mutableListOf<BookEditUiEvent>()
            val root =
                page(
                    ready().copy(visibleRoles = setOf(ContributorRole.AUTHOR), contributors = listOf(fresh)),
                ) { events += it }

            (root.querySelector(".rel-chip .rel-x") as HTMLElement).click()

            events shouldContain BookEditUiEvent.RemoveContributor(fresh, ContributorRole.AUTHOR)
        }

        test("searching in one role's box does not search another's") {
            val events = mutableListOf<BookEditUiEvent>()
            val root =
                page(
                    ready().copy(visibleRoles = setOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR)),
                ) { events += it }

            val narrator = root.querySelector("#edit-role-narrator") as HTMLInputElement
            narrator.value = "simon"
            narrator.dispatchEvent(Event("input", js("({bubbles:true})")))

            events shouldContain BookEditUiEvent.RoleSearchQueryChanged(ContributorRole.NARRATOR, "simon")
        }

        test("a series carries an editable position in it") {
            val series = EditableSeries(id = "s1", name = "Dune", sequence = "1")
            val events = mutableListOf<BookEditUiEvent>()
            val root = page(ready().copy(series = listOf(series))) { events += it }

            val sequence = root.querySelector(".rel-seq") as HTMLInputElement
            sequence.value shouldBe "1"
            sequence.value = "1.5"
            sequence.dispatchEvent(Event("input", js("({bubbles:true})")))

            events shouldContain BookEditUiEvent.SeriesSequenceChanged(series, "1.5")
        }

        test("two series with the same name are told apart by their size") {
            // ⛔ The bug this pins. `RelationChip` was `{ id, label }` with no second line at all,
            // so a library holding two series called "Chronicles" offered the editor two identical
            // rows and no way to know which was which. Both natives show the count.
            val root =
                page(
                    ready().copy(
                        seriesSearchQuery = "chron",
                        seriesSearchResults =
                            listOf(
                                SeriesSearchResult(id = "s1", name = "Chronicles", bookCount = 9),
                                SeriesSearchResult(id = "s2", name = "Chronicles", bookCount = 1),
                            ),
                    ),
                )

            val rows = root.querySelectorAll(".rel-result").asList().filterIsInstance<HTMLElement>()
            rows.map { it.textContent?.trim() } shouldBe listOf("Chronicles9 books", "Chronicles1 book")
        }

        test("a series nobody has used yet gets no second line rather than a zero") {
            // "0 books" is noise on a record that is simply new.
            val root =
                page(
                    ready().copy(
                        seriesSearchQuery = "new",
                        seriesSearchResults = listOf(SeriesSearchResult(id = "s3", name = "Brand New", bookCount = 0)),
                    ),
                )

            root.querySelectorAll(".rel-result-s").length shouldBe 0
        }

        test("a contributor's row carries the same disambiguation") {
            val root =
                page(
                    ready().copy(
                        visibleRoles = setOf(ContributorRole.AUTHOR),
                        roleSearchQueries = mapOf(ContributorRole.AUTHOR to "smith"),
                        roleSearchResults =
                            mapOf(
                                ContributorRole.AUTHOR to
                                    listOf(
                                        ContributorSearchResult(id = "c1", name = "John Smith", bookCount = 12),
                                        ContributorSearchResult(id = "c2", name = "John Smith", bookCount = 2),
                                    ),
                            ),
                    ),
                )

            val rows = root.querySelectorAll(".rel-result").asList().filterIsInstance<HTMLElement>()
            rows.map { it.textContent?.trim() } shouldBe listOf("John Smith12 books", "John Smith2 books")
        }

        test("an attached chip carries no subtitle — it has been chosen already") {
            // The second line exists to choose between candidates. On a chip it is noise in a place
            // with no room for it.
            val root = page(ready().copy(series = listOf(EditableSeries(id = "s1", name = "The Expanse", sequence = "1"))))

            root.querySelectorAll(".rel-chip .rel-result-s").length shouldBe 0
        }

        test("picking a series from search reports the result, not a rebuilt copy") {
            val result = SeriesSearchResult(id = "s9", name = "The Expanse", bookCount = 9)
            val events = mutableListOf<BookEditUiEvent>()
            val root = page(ready().copy(seriesSearchQuery = "exp", seriesSearchResults = listOf(result))) { events += it }

            (root.querySelector(".rel-result") as HTMLElement).click()

            events shouldContain BookEditUiEvent.SeriesSelected(result)
        }

        test("an offline search says so instead of implying nothing matched") {
            val root = page(ready().copy(seriesSearchQuery = "dune", seriesOfflineResult = true))

            root.textContent!! shouldContain "Offline"
        }
    })
