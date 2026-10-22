package com.calypsan.listenup.client.features.bookdetail

import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailSection
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val inProgressOrder =
    listOf(
        BookDetailSection.Chapters,
        BookDetailSection.About,
        BookDetailSection.Readers,
        BookDetailSection.Ratings,
        BookDetailSection.Hardcover,
        BookDetailSection.Details,
    )

private fun ready(
    showScanWarning: Boolean = false,
    isHeld: Boolean = false,
    isPlaybackAvailable: Boolean = true,
    visibility: BookVisibility? = null,
    order: List<BookDetailSection> = inProgressOrder,
) = BookDetailUiState.Ready(
    book =
        BookDetail(
            id = BookId("b1"),
            libraryId = LibraryId("lib"),
            folderId = FolderId("folder"),
            title = "Words of Radiance",
            authors = emptyList(),
            narrators = emptyList(),
            duration = 1_000L,
            coverPath = null,
            addedAt = Timestamp(0L),
            updatedAt = Timestamp(0L),
        ),
    hasScanWarning = showScanWarning,
    showScanWarning = showScanWarning,
    isHeld = isHeld,
    isPlaybackAvailable = isPlaybackAvailable,
    visibility = visibility,
    sectionOrder = order,
)

private fun section(section: BookDetailSection) = PhoneDetailItem.Section(section)

/**
 * The phone column is built by walking [phoneDetailItems], and both the social line and the Readers landing (spec
 * §2.10, §6.4) read Readers' index off that same list, so the scroll target cannot drift from what the column draws.
 */
class BookDetailReadersLandingTest :
    FunSpec({
        test("an ordinary book: hero, actions, social line and last match, then the sections in stage order") {
            val items = phoneDetailItems(ready(), hasSocialLine = true)
            items shouldBe
                listOf(
                    PhoneDetailItem.Hero,
                    PhoneDetailItem.Listen,
                    PhoneDetailItem.Social,
                    PhoneDetailItem.LastMatch,
                    section(BookDetailSection.Chapters),
                    section(BookDetailSection.About),
                    section(BookDetailSection.Readers),
                    section(BookDetailSection.Ratings),
                    section(BookDetailSection.Hardcover),
                    section(BookDetailSection.Details),
                )
            readersItemIndex(items) shouldBe 6
        }

        // The offline reason sits inside the action unit now (spec §2.9), so it moves nothing.
        test("the admins' scan warning and visibility card each push Readers one further down") {
            readersItemIndex(
                phoneDetailItems(ready(showScanWarning = true, visibility = BookVisibility.Stranded), hasSocialLine = true),
            ) shouldBe 8
        }

        test("a book that cannot play has no action unit above Readers") {
            readersItemIndex(phoneDetailItems(ready(isPlaybackAvailable = false), hasSocialLine = true)) shouldBe 5
        }

        test("the stage order moves Readers: not started, it follows About, Ratings and Hardcover") {
            val notStarted =
                listOf(
                    BookDetailSection.About,
                    BookDetailSection.Ratings,
                    BookDetailSection.Hardcover,
                    BookDetailSection.Readers,
                    BookDetailSection.Chapters,
                    BookDetailSection.Details,
                )
            readersItemIndex(phoneDetailItems(ready(order = notStarted), hasSocialLine = false)) shouldBe 6
        }

        test("a held book is triage-only: no social line, rating, Readers or Hardcover, so there is nowhere to land") {
            val items =
                phoneDetailItems(ready(isHeld = true, visibility = BookVisibility.Stranded), hasSocialLine = true)
            items shouldBe
                listOf(
                    PhoneDetailItem.Hero,
                    PhoneDetailItem.Held,
                    PhoneDetailItem.LastMatch,
                    section(BookDetailSection.Chapters),
                    section(BookDetailSection.About),
                    section(BookDetailSection.Details),
                )
            readersItemIndex(items) shouldBe null
        }

        test("every item has its own key, and Readers is keyed readers, the landing's anchor") {
            val items = phoneDetailItems(ready(showScanWarning = true, visibility = BookVisibility.Stranded), true)
            items.map { it.key }.toSet().size shouldBe items.size
            section(BookDetailSection.Readers).key shouldBe "readers"
        }
    })
