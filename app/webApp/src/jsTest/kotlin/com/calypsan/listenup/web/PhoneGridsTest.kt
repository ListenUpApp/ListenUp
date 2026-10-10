package com.calypsan.listenup.web

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.presentation.library.BookStatusCounts
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.web.features.bookedit.BookEditPage
import com.calypsan.listenup.web.features.contributordetail.ContributorDetailPage
import com.calypsan.listenup.web.features.contributordetail.bookItem
import com.calypsan.listenup.web.features.contributordetail.readyContributor
import com.calypsan.listenup.web.features.contributordetail.roleSection
import com.calypsan.listenup.web.features.contributordetail.seriesWithBooks
import com.calypsan.listenup.web.features.library.LibraryPage
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

/** A tile narrower than this stops reading as a cover: the title under it truncates to a letter. */
private const val MIN_TILE_PX = 100.0

/**
 * The cover grids and the minimum widths that used to push phones sideways, measured in the real
 * shell at phone and tablet widths (see [ViewportFrames]).
 *
 * The failure each of these pins was arithmetic before it was a bug: a fixed six-column grid at
 * 320px is about 16px a tile, and a `min-width:260px` card inside a 250px panel has nowhere to go
 * but past the edge.
 */
class PhoneGridsTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun contributor(width: Int) =
            frames.mount(width) {
                InShell {
                    ContributorDetailPage(
                        state =
                            readyContributor(
                                roleSections =
                                    listOf(roleSection(previewBooks = (1..10).map { bookItem("b$it", "Book number $it") })),
                                series = listOf(seriesWithBooks(name = "The Dark Tower, told in seven long books")),
                            ),
                        onOpenLibrary = {},
                        onOpenContributors = {},
                        onOpenBook = {},
                        onConfirmDelete = {},
                        onDismissDeleteError = {},
                    )
                }
            }

        fun columnsOf(
            frame: ViewportFrame,
            selector: String,
        ): Int = frame.css(frame.find(selector), "grid-template-columns").split(" ").count { it.isNotBlank() }

        listOf(SMALL_PHONE, PHONE).forEach { width ->
            test("at ${width}px a contributor's books are a grid of covers, not a row of slivers") {
                val frame = contributor(width)

                columnsOf(frame, ".cd-tile-grid") shouldBeGreaterThanOrEqual 2
                frame.findAll(".cd-tile").forEach { tile ->
                    frame.rect(tile).width shouldBeGreaterThanOrEqual MIN_TILE_PX
                }
                withClue(frame.pastTheEdge()) { frame.contentOverflow() shouldBe 0 }
            }

            test("at ${width}px a series card fits inside its panel") {
                val frame = contributor(width)
                val card = frame.find(".cd-series-card")
                val main = frame.rect(frame.find(".shell-main"))

                frame.rect(card).right shouldBeLessThanOrEqual main.right
                withClue(frame.pastTheEdge()) { frame.contentOverflow() shouldBe 0 }
            }

            test("at ${width}px the book edit form's fields stack instead of spilling") {
                val frame =
                    frames.mount(width) {
                        InShell {
                            BookEditPage(
                                state = BookEditUiState(isLoading = false, bookId = "b1", title = "Dune"),
                                onEvent = {},
                                onOpenLibrary = {},
                                onOpenBook = {},
                            )
                        }
                    }

                frame.findAll(".edit-grid").forEach { grid ->
                    val box = frame.rect(grid)
                    grid.children.let { cells ->
                        (0 until cells.length).forEach { i ->
                            frame.rect(cells.item(i)!!).right shouldBeLessThanOrEqual box.right
                        }
                    }
                }
                withClue(frame.pastTheEdge()) { frame.contentOverflow() shouldBe 0 }
            }

            test("at ${width}px the library shows covers two abreast") {
                val frame =
                    frames.mount(width) {
                        InShell { LibraryPage(state = library(), onEvent = {}, onOpenBook = {}, onSelectFacet = {}) }
                    }
                awaitFrame()

                columnsOf(frame, ".lib-grid") shouldBe 2
                withClue(frame.pastTheEdge()) { frame.contentOverflow() shouldBe 0 }
            }
        }

        test("a tablet fits more than two of a contributor's covers to a row") {
            val frame = contributor(TABLET)

            columnsOf(frame, ".cd-tile-grid") shouldBeGreaterThanOrEqual 3
            frame.findAll(".cd-tile").forEach { tile ->
                frame.rect(tile).width shouldBeGreaterThanOrEqual MIN_TILE_PX
            }
        }
    })

private val TITLE_ASCENDING = SortState(SortCategory.TITLE, SortDirection.ASCENDING)

private fun library(): LibraryUiState.Loaded {
    val books: List<BookListItem> = (1..8).map { bookItem("b$it", "Book number $it") }
    return LibraryUiState.Loaded(
        booksSortState = TITLE_ASCENDING,
        seriesSortState = TITLE_ASCENDING,
        authorsSortState = TITLE_ASCENDING,
        narratorsSortState = TITLE_ASCENDING,
        ignoreTitleArticles = false,
        hideSingleBookSeries = false,
        contentRevision = 1L,
        books = books,
        series = emptyList(),
        authors = emptyList(),
        narrators = emptyList(),
        seriesProgress = emptyMap(),
        syncState = SyncState.Idle,
        isServerScanning = false,
        scanProgress = null,
        isBuildingInitialLibrary = false,
        statusCounts = BookStatusCounts(all = books.size, inProgress = 0, notStarted = books.size, finished = 0),
    )
}
