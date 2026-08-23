package com.calypsan.listenup.web.features.contributors

import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.core.ContributorId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

private fun contributor(
    id: String,
    name: String,
    bookCount: Int = 1,
): ContributorWithBookCount =
    ContributorWithBookCount(
        contributor = Contributor(id = ContributorId(id), name = name),
        bookCount = bookCount,
    )

private fun contributorsPage(
    state: List<ContributorWithBookCount>,
    role: String = ContributorRole.AUTHOR.apiValue,
    onSelectRole: (String) -> Unit = {},
    onOpenContributor: (String) -> Unit = {},
): HTMLElement {
    val root = document.createElement("div") as HTMLElement
    document.body?.appendChild(root)
    renderComposable(root = root) {
        ContributorsPage(
            state = state,
            role = role,
            onSelectRole = onSelectRole,
            onOpenContributor = onOpenContributor,
        )
    }
    return root
}

/**
 * The Contributors list rendered against a fixed session (Task A1 — no routing yet).
 *
 * What these pin: a row shows the person's own facts and nothing invented (see the hours
 * deviation noted on [ContributorsPage]), the A→Z rail groups in the right order, and both
 * gestures — the role toggle and a row itself — leave as the caller's own event rather than
 * being decided in here.
 */
class ContributorsPageTest :
    FunSpec({

        test("rows render the contributor's name, role chip, and book count") {
            val root =
                contributorsPage(
                    state = listOf(contributor("c1", "Andrew Peterson", bookCount = 6)),
                )

            root.textContent!! shouldContain "Andrew Peterson"
            root.textContent!! shouldContain "6 books"
            (root.querySelector(".contrib-role-chip") as HTMLElement).textContent shouldBe "Author"
        }

        test("a single book reads as '1 book', not '1 books'") {
            val root = contributorsPage(state = listOf(contributor("c1", "Andy Weir", bookCount = 1)))

            root.textContent!! shouldContain "1 book"
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

        test("the narrator chip fires onSelectRole with the narrator role") {
            val selected = mutableListOf<String>()
            val root =
                contributorsPage(
                    state = listOf(contributor("c1", "Andy Weir")),
                    onSelectRole = { selected += it },
                )

            root.querySelectorAll(".contrib-toggle-chip").let { chips ->
                (0 until chips.length)
                    .map { chips.item(it) as HTMLElement }
                    .first { it.textContent == "Narrators" }
                    .click()
            }

            selected shouldContain ContributorRole.NARRATOR.apiValue
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
            val authors = contributorsPage(state = emptyList(), role = ContributorRole.AUTHOR.apiValue)
            authors.textContent!! shouldContain "No authors yet."

            val narrators = contributorsPage(state = emptyList(), role = ContributorRole.NARRATOR.apiValue)
            narrators.textContent!! shouldContain "No narrators yet."
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
    })
