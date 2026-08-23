package com.calypsan.listenup.web.features.contributordetail

import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

private fun contributorDetailPage(
    state: ContributorDetailUiState,
    onOpenLibrary: () -> Unit = {},
    onOpenContributors: () -> Unit = {},
    onOpenBook: (String) -> Unit = {},
): HTMLElement {
    val root = document.createElement("div") as HTMLElement
    document.body?.appendChild(root)
    renderComposable(root = root) {
        ContributorDetailPage(
            state = state,
            onOpenLibrary = onOpenLibrary,
            onOpenContributors = onOpenContributors,
            onOpenBook = onOpenBook,
        )
    }
    return root
}

/**
 * Contributor Detail rendered against the shared [ContributorDetailUiState] (Task B1 — no routing
 * yet). What these pin: the hero renders the contributor's own facts and nothing invented, each
 * role panel's count badge is the true total rather than the preview length, the credited-as line
 * appears only when an alias genuinely differs from the contributor's name, a progress underline
 * only marks a book [ContributorDetailUiState.Ready.bookProgress] actually knows about, every
 * non-Ready state offers its own explanation and a way back, and the series panel appears only
 * when there is a series to show.
 */
class ContributorDetailPageTest :
    FunSpec({

        test("the hero renders the contributor's name and both stat pills") {
            val root =
                contributorDetailPage(
                    readyContributor(name = "Stephen King", bookCount = 64),
                )

            (root.querySelector(".cd-name") as HTMLElement).textContent shouldBe "Stephen King"
            val stats = root.querySelectorAll(".cd-stat")
            (stats.item(0) as HTMLElement).textContent.orEmpty() shouldContain "64 books"
            (stats.item(1) as HTMLElement).textContent.orEmpty() shouldContain "of audio"
        }

        test("a single book reads as '1 book', not '1 books'") {
            val root = contributorDetailPage(readyContributor(bookCount = 1))

            (root.querySelector(".cd-stat") as HTMLElement).textContent.orEmpty() shouldContain "1 book"
        }

        test("the audio stat never says 'listened' — it is library duration, not listening history") {
            val root = contributorDetailPage(readyContributor())

            val stats = root.querySelectorAll(".cd-stat")
            (stats.item(1) as HTMLElement).textContent.orEmpty() shouldContain "of audio"
            root.textContent.orEmpty() shouldContain "of audio"
        }

        test("one role chip renders per role section") {
            val root =
                contributorDetailPage(
                    readyContributor(
                        roleSections =
                            listOf(
                                roleSection(role = ContributorRole.AUTHOR.apiValue, displayName = "Written By"),
                                roleSection(role = ContributorRole.NARRATOR.apiValue, displayName = "Narrated By"),
                            ),
                    ),
                )

            val chips = root.querySelectorAll(".cd-role-chip")
            chips.length shouldBe 2
            (chips.item(0) as HTMLElement).textContent shouldBe "Author"
            (chips.item(1) as HTMLElement).textContent shouldBe "Narrator"
        }

        test("a panel's count badge shows the role's true total, not the preview length") {
            val root =
                contributorDetailPage(
                    readyContributor(
                        roleSections =
                            listOf(
                                roleSection(
                                    displayName = "Written By",
                                    bookCount = 58,
                                    previewBooks = listOf(bookItem("b1", "The Institute")),
                                ),
                            ),
                    ),
                )

            // The regression this guards: a badge that silently fell back to the preview length (1).
            (root.querySelector(".cd-count-badge") as HTMLElement).textContent shouldBe "58"
            root.querySelectorAll(".cd-tile").length shouldBe 1
        }

        test("one panel renders per role section, each carrying its own display name") {
            val root =
                contributorDetailPage(
                    readyContributor(
                        roleSections =
                            listOf(
                                roleSection(role = ContributorRole.AUTHOR.apiValue, displayName = "Written By"),
                                roleSection(role = ContributorRole.NARRATOR.apiValue, displayName = "Narrated By"),
                            ),
                    ),
                )

            val sections = root.querySelectorAll(".cd-role-section")
            sections.length shouldBe 2
            val headings = root.querySelectorAll(".cd-role-section h3")
            (headings.item(0) as HTMLElement).textContent shouldBe "Written By"
            (headings.item(1) as HTMLElement).textContent shouldBe "Narrated By"
        }

        test("a tile click opens the book it stands for") {
            val opened = mutableListOf<String>()
            val root =
                contributorDetailPage(
                    readyContributor(
                        roleSections = listOf(roleSection(previewBooks = listOf(bookItem("b-institute", "The Institute")))),
                    ),
                    onOpenBook = { opened += it },
                )

            (root.querySelector(".cd-tile") as HTMLElement).click()

            opened shouldBe listOf("b-institute")
        }

        test("the credited-as line appears when an alias differs from the contributor's name") {
            val root =
                contributorDetailPage(
                    readyContributor(
                        roleSections = listOf(roleSection(previewBooks = listOf(bookItem("b1", "The Institute")))),
                        bookCreditedAs = mapOf("b1" to "Richard Bachman"),
                    ),
                )

            (root.querySelector(".cd-alias") as HTMLElement).textContent.orEmpty() shouldContain "Richard Bachman"
        }

        test("the credited-as line is absent when no alias differs from the contributor's name") {
            val root =
                contributorDetailPage(
                    readyContributor(
                        roleSections = listOf(roleSection(previewBooks = listOf(bookItem("b1", "The Institute")))),
                        bookCreditedAs = emptyMap(),
                    ),
                )

            (root.querySelector(".cd-alias") == null) shouldBe true
        }

        test("a tile with a known progress fraction shows the progress underline") {
            val root =
                contributorDetailPage(
                    readyContributor(
                        roleSections =
                            listOf(
                                roleSection(
                                    previewBooks =
                                        listOf(bookItem("b-in-progress", "Fairy Tale"), bookItem("b-untracked", "Holly")),
                                ),
                            ),
                        bookProgress = mapOf(BookId("b-in-progress") to 0.62f),
                    ),
                )

            val tiles = root.querySelectorAll(".cd-tile")
            ((tiles.item(0) as HTMLElement).querySelector(".cd-tile-progress") != null) shouldBe true
            ((tiles.item(1) as HTMLElement).querySelector(".cd-tile-progress") != null) shouldBe false
        }

        test("Loading renders the breadcrumb and says it is loading, not a blank frame") {
            val root = contributorDetailPage(ContributorDetailUiState.Loading)

            (root.querySelector(".crumb") != null) shouldBe true
            root.textContent.orEmpty() shouldContain "Loading"
        }

        test("Error renders its message and a way back to Contributors") {
            val root = contributorDetailPage(ContributorDetailUiState.Error("The server could not be reached."))

            root.querySelector(".empty")!!.textContent.orEmpty() shouldContain "The server could not be reached."
            (root.querySelector(".empty button") != null) shouldBe true
        }

        test("NotFound explains itself honestly and offers a way back") {
            val root = contributorDetailPage(ContributorDetailUiState.NotFound)

            val text = root.querySelector(".empty")!!.textContent.orEmpty()
            text shouldContain "merged"
            (root.querySelector(".empty button") != null) shouldBe true
        }

        test("the way-back button on a terminal state opens Contributors") {
            var opened = false
            val root =
                contributorDetailPage(ContributorDetailUiState.NotFound, onOpenContributors = { opened = true })

            (root.querySelector(".empty button") as HTMLElement).click()

            opened shouldBe true
        }

        test("the series panel renders a card per series when series exist") {
            val root =
                contributorDetailPage(
                    readyContributor(series = listOf(seriesWithBooks(name = "The Dark Tower"))),
                )

            val cards = root.querySelectorAll(".cd-series-card")
            cards.length shouldBe 1
            (root.querySelector(".cd-series-name") as HTMLElement).textContent shouldBe "The Dark Tower"
        }

        test("the series panel is absent when there is no series") {
            val root = contributorDetailPage(readyContributor(series = emptyList()))

            (root.querySelector(".cd-series-card") == null) shouldBe true
        }
    })
