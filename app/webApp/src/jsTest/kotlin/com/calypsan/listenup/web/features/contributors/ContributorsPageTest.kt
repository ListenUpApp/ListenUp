package com.calypsan.listenup.web.features.contributors

import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.presentation.library.LetterSection
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.LibraryFacet
import com.calypsan.listenup.web.features.library.lettersOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

/** Opens the page's Sort dropdown and waits for the menu to render. */
private suspend fun openSort(root: HTMLElement) {
    (root.querySelector(".lib-sort-trigger") as HTMLElement).click()
    awaitFrame()
}

private fun EventTarget.press(key: String) {
    dispatchEvent(
        KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)),
    )
}

/**
 * The Contributors list rendered against a fixed session (Task A1 — no routing yet).
 *
 * What these pin: a row shows the person's own facts and nothing invented (see the hours
 * deviation noted on [ContributorsPage]), the A→Z rail groups in the right order, both gestures —
 * the role toggle and a row itself — leave as the caller's own event rather than being decided in
 * here, an unanswered query never gets mistaken for an empty one, and the row and role chip stay
 * reachable by keyboard.
 */
class ContributorsPageTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun contributorsPage(
            state: List<ContributorWithBookCount>?,
            role: ContributorRole = ContributorRole.AUTHOR,
            onSelectFacet: (LibraryFacet) -> Unit = {},
            onOpenContributor: (String) -> Unit = {},
            sortState: SortState = SortState(SortCategory.NAME, SortDirection.ASCENDING),
            onEvent: (LibraryUiEvent) -> Unit = {},
            // What the shared state carries: letters on a name sort, none on any other.
            letters: List<LetterSection> =
                if (sortState.category == SortCategory.NAME) lettersOf(state.orEmpty().map { it.contributor.name }) else emptyList(),
        ): HTMLElement =
            mounts.mount {
                ContributorsPage(
                    sortState = sortState,
                    onEvent = onEvent,
                    letters = letters,
                    state = state,
                    role = role,
                    onSelectFacet = onSelectFacet,
                    onOpenContributor = onOpenContributor,
                )
            }

        test("rows render the contributor's name, role chip, and book count") {
            val root =
                contributorsPage(
                    state = listOf(contributor("c1", "Andrew Peterson", bookCount = 6)),
                )

            root.textContent!! shouldContain "Andrew Peterson"
            (root.querySelector(".contrib-role-chip") as HTMLElement).textContent shouldBe "Author"
            (root.querySelector(".contrib-book-count") as HTMLElement).textContent shouldBe "6 books"
        }

        test("a single book reads as '1 book', not '1 books'") {
            val root = contributorsPage(state = listOf(contributor("c1", "Andy Weir", bookCount = 1)))

            (root.querySelector(".contrib-book-count") as HTMLElement).textContent shouldBe "1 book"
        }

        // The people arrive already ordered by the shared ViewModel, so the fixture supplies them
        // that way; what this pins is that the rail honours that order and that a person's name is
        // never article-stripped — "The Kingkiller Trio" files under T, not K.
        test("letter sections follow the caller's order; a person's name is never article-stripped") {
            val root =
                contributorsPage(
                    state =
                        listOf(
                            contributor("c2", "Andy Weir"),
                            contributor("c3", "The Kingkiller Trio"),
                            contributor("c1", "Zoe Quinn"),
                        ),
                )

            val letters =
                root.querySelectorAll(".contrib-letter").let { nodes ->
                    (0 until nodes.length).map { (nodes.item(it) as HTMLElement).textContent }
                }
            letters shouldBe listOf("A", "T", "Z")
        }

        test("the narrator chip fires onSelectFacet with the narrator facet") {
            val selected = mutableListOf<LibraryFacet>()
            val root =
                contributorsPage(
                    state = listOf(contributor("c1", "Andy Weir")),
                    onSelectFacet = { selected += it },
                )

            root.querySelectorAll(".facet-chip").let { chips ->
                (0 until chips.length)
                    .map { chips.item(it) as HTMLElement }
                    .first { it.textContent == "Narrators" }
                    .click()
            }

            selected shouldContain LibraryFacet.Narrators
        }

        test("a row click fires onOpenContributor with the contributor's id") {
            val opened = mutableListOf<String>()
            val root =
                contributorsPage(
                    state = listOf(contributor("c-andy", "Andy Weir")),
                    onOpenContributor = { opened += it },
                )

            (root.querySelector(".contrib-row") as HTMLElement).click()

            opened shouldContain "c-andy"
        }

        test("an empty list renders honest, role-specific text rather than a blank box") {
            val authors = contributorsPage(state = emptyList(), role = ContributorRole.AUTHOR)
            authors.textContent!! shouldContain "No authors yet."

            val narrators = contributorsPage(state = emptyList(), role = ContributorRole.NARRATOR)
            narrators.textContent!! shouldContain "No narrators yet."
        }

        test("a null state renders Loading, not the empty-role text") {
            val root = contributorsPage(state = null, role = ContributorRole.AUTHOR)

            root.textContent!! shouldContain "Loading…"
            root.textContent!! shouldNotContain "No authors yet."
        }

        test("an empty list renders the empty text, not Loading") {
            val root = contributorsPage(state = emptyList(), role = ContributorRole.AUTHOR)

            root.textContent!! shouldContain "No authors yet."
            root.textContent!! shouldNotContain "Loading…"
        }

        test("headings come from the shared letter sections, keyed to the people they cover") {
            val root =
                contributorsPage(
                    state = listOf(contributor("c1", "Zoe Quinn"), contributor("c2", "Andy Weir"), contributor("c3", "3 Doors Down")),
                    letters = listOf(LetterSection("Z", 0, 1), LetterSection("A", 1, 1), LetterSection("#", 2, 1)),
                )

            root.querySelectorAll(".contrib-letter").asList().map { it.textContent } shouldBe listOf("Z", "A", "#")
        }

        test("the active facet chip carries aria-pressed=true and the others false") {
            val root = contributorsPage(state = emptyList(), role = ContributorRole.AUTHOR)

            val chips = root.querySelectorAll(".facet-chip")
            val authors = (0 until chips.length).map { chips.item(it) as HTMLElement }.first { it.textContent == "Authors" }
            val narrators =
                (0 until chips.length).map { chips.item(it) as HTMLElement }.first { it.textContent == "Narrators" }

            authors.getAttribute("aria-pressed") shouldBe "true"
            authors.classList.contains("is-active") shouldBe true
            narrators.getAttribute("aria-pressed") shouldBe "false"
            narrators.classList.contains("is-active") shouldBe false
        }

        test("a contributor row is reachable by keyboard and announces itself as a control") {
            val root = contributorsPage(state = listOf(contributor("c1", "Andy Weir")))

            val row = root.querySelector(".contrib-row") as HTMLElement
            row.getAttribute("tabindex") shouldBe "0"
            row.getAttribute("role") shouldBe "button"
        }

        test("Enter opens the contributor the row is for") {
            val opened = mutableListOf<String>()
            val root =
                contributorsPage(
                    state = listOf(contributor("c-andy", "Andy Weir")),
                    onOpenContributor = { opened += it },
                )

            (root.querySelector(".contrib-row") as HTMLElement).press("Enter")

            opened shouldBe listOf("c-andy")
        }

        test("Space opens the contributor row too, as a button would") {
            val opened = mutableListOf<String>()
            val root =
                contributorsPage(
                    state = listOf(contributor("c-andy", "Andy Weir")),
                    onOpenContributor = { opened += it },
                )

            (root.querySelector(".contrib-row") as HTMLElement).press(" ")

            opened shouldBe listOf("c-andy")
        }

        test("a key that is not an activation leaves the contributor row alone") {
            val opened = mutableListOf<String>()
            val root =
                contributorsPage(
                    state = listOf(contributor("c-andy", "Andy Weir")),
                    onOpenContributor = { opened += it },
                )

            (root.querySelector(".contrib-row") as HTMLElement).press("ArrowDown")

            opened shouldBe emptyList()
        }

        test("a facet chip is reachable by keyboard and announces itself as a control") {
            val root = contributorsPage(state = emptyList())

            val chip = root.querySelector(".facet-chip") as HTMLElement
            chip.getAttribute("tabindex") shouldBe "0"
            chip.getAttribute("role") shouldBe "button"
        }

        test("Enter activates a facet chip") {
            val selected = mutableListOf<LibraryFacet>()
            val root = contributorsPage(state = emptyList(), onSelectFacet = { selected += it })

            root.querySelectorAll(".facet-chip").let { chips ->
                (0 until chips.length)
                    .map { chips.item(it) as HTMLElement }
                    .first { it.textContent == "Narrators" }
                    .press("Enter")
            }

            selected shouldBe listOf(LibraryFacet.Narrators)
        }

        test("Space activates a facet chip too") {
            val selected = mutableListOf<LibraryFacet>()
            val root = contributorsPage(state = emptyList(), onSelectFacet = { selected += it })

            root.querySelectorAll(".facet-chip").let { chips ->
                (0 until chips.length)
                    .map { chips.item(it) as HTMLElement }
                    .first { it.textContent == "Narrators" }
                    .press(" ")
            }

            selected shouldBe listOf(LibraryFacet.Narrators)
        }

        test("a key that is not an activation leaves the facet chip alone") {
            val selected = mutableListOf<LibraryFacet>()
            val root = contributorsPage(state = emptyList(), onSelectFacet = { selected += it })

            root.querySelectorAll(".facet-chip").let { chips ->
                (0 until chips.length)
                    .map { chips.item(it) as HTMLElement }
                    .first { it.textContent == "Narrators" }
                    .press("ArrowDown")
            }

            selected shouldBe emptyList()
        }

        test("the avatar is decorative and hidden from the row's accessible name") {
            val root = contributorsPage(state = listOf(contributor("c1", "Andy Weir")))

            (root.querySelector(".contrib-avatar") as HTMLElement).getAttribute("aria-hidden") shouldBe "true"
        }
        test("the sort menu offers the two categories a person list can be ordered by, and no Ignore articles") {
            val root = contributorsPage(listOf(contributor("c1", "Andy Weir", 3)))
            openSort(root)

            root.querySelectorAll("[role=group][aria-label=\"Sort by\"] [role=menuitemradio]").asList().map { it.textContent } shouldBe
                listOf("Name", "Number of books")
            root.querySelector("[role=menuitemcheckbox]") shouldBe null
        }

        test("choosing a category reports the Authors event when Authors is showing") {
            val events = mutableListOf<LibraryUiEvent>()
            val root = contributorsPage(listOf(contributor("c1", "Andy Weir", 3)), onEvent = { events += it })
            openSort(root)

            (root.querySelectorAll("[role=menuitemradio]").item(1) as HTMLElement).click()

            events shouldBe listOf(LibraryUiEvent.AuthorsCategoryChanged(SortCategory.BOOK_COUNT))
        }

        // ⛔ The two lists keep separate sorts. Sorting Narrators by book count must not silently
        // reorder Authors, which a single shared event would do.
        test("choosing a category reports the Narrators event when Narrators is showing") {
            val events = mutableListOf<LibraryUiEvent>()
            val root =
                contributorsPage(
                    listOf(contributor("c1", "Rosamund Pike", 9)),
                    role = ContributorRole.NARRATOR,
                    onEvent = { events += it },
                )
            openSort(root)

            (root.querySelectorAll("[role=menuitemradio]").item(1) as HTMLElement).click()

            events shouldBe listOf(LibraryUiEvent.NarratorsCategoryChanged(SortCategory.BOOK_COUNT))
        }

        test("choosing the other direction reports the role's own toggle") {
            val events = mutableListOf<LibraryUiEvent>()
            val root =
                contributorsPage(
                    listOf(contributor("c1", "Rosamund Pike", 9)),
                    role = ContributorRole.NARRATOR,
                    onEvent = { events += it },
                )

            openSort(root)
            // Z → A: the direction not in force.
            (root.querySelectorAll("[role=group][aria-label=\"Direction\"] [role=menuitemradio]").item(1) as HTMLElement).click()

            events shouldBe listOf(LibraryUiEvent.NarratorsDirectionToggled)
        }

        // ⛔ The bug the old local `sortedBy { name.lowercase() }` would cause once the ViewModel
        // owns ordering: the page would silently re-alphabetise a list the reader asked to see by
        // book count.
        test("a book-count sort keeps the ViewModel's order rather than re-alphabetising it") {
            val root =
                contributorsPage(
                    listOf(
                        contributor("c1", "Zoe Quinn", 47),
                        contributor("c2", "Andy Weir", 3),
                    ),
                    sortState = SortState(SortCategory.BOOK_COUNT, SortDirection.DESCENDING),
                )

            root.querySelectorAll(".contrib-name").asList().map { it.textContent } shouldBe
                listOf("Zoe Quinn", "Andy Weir")
        }

        // The rail labels runs of people with a letter. Under "Most books" that letter means
        // nothing — the same call the Books tab makes for its Added and Duration sorts.
        test("the letter rail belongs to a name sort and disappears under any other") {
            val people = listOf(contributor("c1", "Andy Weir", 3), contributor("c2", "Zoe Quinn", 47))

            contributorsPage(people).querySelectorAll(".contrib-letter").length shouldBe 2
            contributorsPage(
                people,
                sortState = SortState(SortCategory.BOOK_COUNT, SortDirection.DESCENDING),
            ).querySelectorAll(".contrib-letter").length shouldBe 0
        }

        // ⛔ The rail used to be re-sorted A→Z here regardless of the sort it was rendering, so a
        // reader who pressed ↓ on a name sort saw the people inside each letter reverse while the
        // letters themselves stayed put. The sections follow the caller's order now.
        test("a descending name sort runs the letter rail Z to A, not A to Z") {
            val root =
                contributorsPage(
                    listOf(contributor("c2", "Zoe Quinn", 47), contributor("c1", "Andy Weir", 3)),
                    sortState = SortState(SortCategory.NAME, SortDirection.DESCENDING),
                )

            root.querySelectorAll(".contrib-letter").asList().map { it.textContent } shouldBe listOf("Z", "A")
        }

        test("an ascending name sort still runs A to Z") {
            val root =
                contributorsPage(
                    listOf(contributor("c1", "Andy Weir", 3), contributor("c2", "Zoe Quinn", 47)),
                    sortState = SortState(SortCategory.NAME, SortDirection.ASCENDING),
                )

            root.querySelectorAll(".contrib-letter").asList().map { it.textContent } shouldBe listOf("A", "Z")
        }

        // Spec L25/L26: Authors and Narrators by name get the same A–Z jump rail as Books.
        test("a name-sorted list gets the jump rail, which jumps to the letter's first person") {
            val people =
                listOf(contributor("c1", "Andy Weir", 3), contributor("c2", "Ann Leckie", 4), contributor("c3", "Zoe Quinn", 47))
            val root = contributorsPage(people)

            val jumpZ = root.querySelector(".lib-rail button[aria-label=\"Jump to Z\"]") as HTMLElement
            jumpZ.click()
            awaitFrame()

            (kotlinx.browser.document.activeElement as HTMLElement).textContent!! shouldContain "Zoe Quinn"
        }

        test("no rail under a book-count sort") {
            val people = listOf(contributor("c1", "Andy Weir", 3), contributor("c2", "Zoe Quinn", 47))
            contributorsPage(people, sortState = SortState(SortCategory.BOOK_COUNT, SortDirection.DESCENDING))
                .querySelector(".lib-rail") shouldBe null
        }

        // Offering to reorder nothing is an affordance whose only outcome is nothing — the same
        // rule the Library's own header follows.
        test("sorting is offered only once the list has actually answered") {
            contributorsPage(null).querySelectorAll(".lib-sort-trigger").length shouldBe 0
            contributorsPage(listOf(contributor("c1", "Andy Weir", 3)))
                .querySelectorAll(".lib-sort-trigger")
                .length shouldBe 1
        }
    })
