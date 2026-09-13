package com.calypsan.listenup.web.features.contributordetail

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.contributordetail.ContributorBooksUiState
import com.calypsan.listenup.client.presentation.contributordetail.SeriesGroup
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLElement

/**
 * Contributor Books — the per-role "View all" list.
 *
 * What these pin: the header counts every book the state carries rather than one group's worth,
 * the series groups keep the ViewModel's order and each reports its own size, "Other Books" names
 * a contrast only when there is a series to contrast with, a progress underline marks only a book
 * the state knows a position for, the alias line appears only when an alias exists, and both
 * non-Ready states offer a way back to the person rather than a dead end.
 */
class ContributorBooksPageTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun booksPage(
            state: ContributorBooksUiState,
            onOpenLibrary: () -> Unit = {},
            onOpenContributors: () -> Unit = {},
            onOpenContributor: () -> Unit = {},
            onOpenBook: (String) -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                ContributorBooksPage(
                    state = state,
                    onOpenLibrary = onOpenLibrary,
                    onOpenContributors = onOpenContributors,
                    onOpenContributor = onOpenContributor,
                    onOpenBook = onOpenBook,
                )
            }

        test("the header names the role and counts every book across all groups") {
            val root =
                booksPage(
                    readyBooks(
                        seriesGroups = listOf(seriesGroup("The Dark Tower", listOf(bookItem("b1", "The Gunslinger")))),
                        standaloneBooks = listOf(bookItem("b2", "The Institute"), bookItem("b3", "Misery")),
                    ),
                )

            (root.querySelector(".cb-role") as HTMLElement).textContent shouldBe "Written By"
            (root.querySelector(".cb-by") as HTMLElement).textContent shouldBe "3 books by Stephen King"
        }

        test("one book reads as one book, not one books") {
            val root = booksPage(readyBooks(standaloneBooks = listOf(bookItem("b1", "Misery"))))

            (root.querySelector(".cb-by") as HTMLElement).textContent shouldBe "1 book by Stephen King"
        }

        test("series groups keep the ViewModel's order and each reports its own size") {
            val root =
                booksPage(
                    readyBooks(
                        seriesGroups =
                            listOf(
                                seriesGroup("Billy Summers", listOf(bookItem("b1", "One"))),
                                seriesGroup(
                                    "The Dark Tower",
                                    listOf(bookItem("b2", "The Gunslinger"), bookItem("b3", "The Drawing of the Three")),
                                ),
                            ),
                    ),
                )

            val headings = root.querySelectorAll(".cb-series h3")
            headings.length shouldBe 2
            (headings.item(0) as HTMLElement).textContent shouldBe "Billy Summers"
            (headings.item(1) as HTMLElement).textContent shouldBe "The Dark Tower"

            val badges = root.querySelectorAll(".cb-series .cd-count-badge")
            (badges.item(0) as HTMLElement).textContent shouldBe "1"
            (badges.item(1) as HTMLElement).textContent shouldBe "2"
        }

        test("standalone books are titled Other Books only when a series shares the page") {
            val root =
                booksPage(
                    readyBooks(
                        seriesGroups = listOf(seriesGroup("The Dark Tower", listOf(bookItem("b1", "The Gunslinger")))),
                        standaloneBooks = listOf(bookItem("b2", "Misery")),
                    ),
                )

            (root.querySelector(".cb-standalone h3") as HTMLElement).textContent shouldBe "Other Books"
        }

        test("a page of nothing but standalone books has no Other Books heading to contrast with") {
            val root = booksPage(readyBooks(standaloneBooks = listOf(bookItem("b1", "Misery"))))

            root.querySelector(".cb-standalone h3") shouldBe null
            root.querySelectorAll(".cd-tile").length shouldBe 1
        }

        test("a progress underline marks only a book the state knows a position for") {
            val started = bookItem("b1", "The Institute")
            val untouched = bookItem("b2", "Misery")
            val root =
                booksPage(
                    readyBooks(
                        standaloneBooks = listOf(started, untouched),
                        bookProgress = mapOf(started.id to 0.5f),
                    ),
                )

            root.querySelectorAll(".cd-tile").length shouldBe 2
            root.querySelectorAll(".cd-tile-progress").length shouldBe 1
        }

        test("clicking a tile opens that book, not the first one on the page") {
            var opened: String? = null
            val root =
                booksPage(
                    readyBooks(standaloneBooks = listOf(bookItem("b1", "Misery"), bookItem("b2", "The Institute"))),
                    onOpenBook = { opened = it },
                )

            (root.querySelectorAll(".cd-tile").item(1) as HTMLElement).click()

            opened shouldBe "b2"
        }

        test("the alias line renders when this role's books credit another name") {
            val root =
                booksPage(
                    readyBooks(
                        standaloneBooks = listOf(bookItem("b1", "Naked in Death")),
                        bookCreditedAs = mapOf("b1" to "J.D. Robb"),
                    ),
                )

            (root.querySelector(".cb-alias") as HTMLElement).textContent shouldContain "J.D. Robb"
        }

        test("no alias line at all when every book credits the person's own name") {
            val root = booksPage(readyBooks(standaloneBooks = listOf(bookItem("b1", "Misery"))))

            root.querySelector(".cb-alias") shouldBe null
        }

        test("a Ready state with no books says so rather than heading an empty page") {
            val root = booksPage(readyBooks())

            (root.querySelector(".empty h3") as HTMLElement).textContent shouldBe "No books in this role"
            root.querySelectorAll(".cd-tile").length shouldBe 0
        }

        test("an error offers the way back to the person, not to the list two levels up") {
            var backToContributor = 0
            val root =
                booksPage(
                    ContributorBooksUiState.Error("The library is unreachable."),
                    onOpenContributor = { backToContributor++ },
                )

            (root.querySelector(".empty p") as HTMLElement).textContent shouldBe "The library is unreachable."
            (root.querySelector(".empty button") as HTMLElement).click()

            backToContributor shouldBe 1
        }

        test("loading says so and still offers the breadcrumb out") {
            val root = booksPage(ContributorBooksUiState.Loading)

            (root.querySelector(".empty p") as HTMLElement).textContent shouldBe "Loading…"
            root.querySelector(".crumb") shouldNotBe null
        }

        test("the third crumb goes back to the contributor, not the contributors list") {
            var toContributor = 0
            var toContributors = 0
            val root =
                booksPage(
                    readyBooks(standaloneBooks = listOf(bookItem("b1", "Misery"))),
                    onOpenContributors = { toContributors++ },
                    onOpenContributor = { toContributor++ },
                )

            val crumbs = root.querySelectorAll(".crumb a")
            (crumbs.item(2) as HTMLElement).click()

            toContributor shouldBe 1
            toContributors shouldBe 0
        }
    })

private fun readyBooks(
    contributorName: String = "Stephen King",
    roleDisplayName: String = "Written By",
    seriesGroups: List<SeriesGroup> = emptyList(),
    standaloneBooks: List<BookListItem> = emptyList(),
    bookProgress: Map<BookId, Float> = emptyMap(),
    bookCreditedAs: Map<String, String> = emptyMap(),
): ContributorBooksUiState.Ready =
    ContributorBooksUiState.Ready(
        contributorName = contributorName,
        roleDisplayName = roleDisplayName,
        seriesGroups = seriesGroups,
        standaloneBooks = standaloneBooks,
        bookProgress = bookProgress,
        bookCreditedAs = bookCreditedAs,
    )

private fun seriesGroup(
    name: String,
    books: List<BookListItem>,
): SeriesGroup = SeriesGroup(seriesName = name, books = books)
