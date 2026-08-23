package com.calypsan.listenup.web.features.contributors

import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.web.design.LibraryFacet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

private fun contributorsPage(
    state: List<ContributorWithBookCount>?,
    role: ContributorRole = ContributorRole.AUTHOR,
    onSelectFacet: (LibraryFacet) -> Unit = {},
    onOpenContributor: (String) -> Unit = {},
): HTMLElement {
    val root = document.createElement("div") as HTMLElement
    document.body?.appendChild(root)
    renderComposable(root = root) {
        ContributorsPage(
            state = state,
            role = role,
            onSelectFacet = onSelectFacet,
            onOpenContributor = onOpenContributor,
        )
    }
    return root
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

        test("letter sections appear in order; a person's name is never article-stripped") {
            val root =
                contributorsPage(
                    state =
                        listOf(
                            contributor("c1", "Zoe Quinn"),
                            contributor("c2", "Andy Weir"),
                            contributor("c3", "The Kingkiller Trio"),
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

        test("groupByLetter sorts A→Z with '#' first, for names with no leading letter") {
            val groups =
                groupByLetter(
                    listOf(
                        contributor("c1", "Zoe Quinn"),
                        contributor("c2", "Andy Weir"),
                        contributor("c3", "3 Doors Down"),
                    ),
                )

            groups.map { it.letter } shouldBe listOf('#', 'A', 'Z')
        }

        test("groupByLetter never article-strips a person's name, matching the shared nameLetter rule") {
            groupByLetter(listOf(contributor("c1", "The Kingkiller Trio"))).single().letter shouldBe 'T'
            groupByLetter(listOf(contributor("c2", "A Perfect Circle"))).single().letter shouldBe 'A'
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
    })
