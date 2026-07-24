package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

/**
 * Tests for [BookListItem] convenience properties.
 *
 * Covers:
 * - Duration formatting
 * - Series title formatting
 * - Cover availability checks
 * - Contributor name formatting
 *
 * [BookDetail] exposes the same convenience properties; if their behavior diverges
 * in the future, mirror these tests on [BookDetail].
 */
class BookListItemTest :
    FunSpec({

        fun createTestBook(
            duration: Long = 3600000L, // 1 hour
            coverPath: String? = null,
            authors: List<BookContributor> = emptyList(),
            narrators: List<BookContributor> = emptyList(),
            series: List<BookSeries> = emptyList(),
        ): BookListItem =
            BookListItem(
                id = BookId("book-1"),
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = "Test Book",
                subtitle = null,
                authors = authors,
                narrators = narrators,
                duration = duration,
                coverPath = coverPath,
                addedAt = Timestamp.now(),
                updatedAt = Timestamp.now(),
                series = series,
            )

        // ========== Duration Formatting Tests ==========

        test("formatDuration returns hours and minutes for long duration") {
            val book = createTestBook(duration = 9_000_000L) // 2.5 hours (150 minutes)
            book.formatDuration() shouldBe "2h 30m"
        }

        test("formatDuration returns only minutes for short duration") {
            val book = createTestBook(duration = 2_700_000L) // 45 minutes
            book.formatDuration() shouldBe "45m"
        }

        test("formatDuration handles exactly one hour") {
            val book = createTestBook(duration = 3_600_000L) // 60 minutes
            book.formatDuration() shouldBe "1h 0m"
        }

        test("formatDuration handles zero duration") {
            val book = createTestBook(duration = 0L)
            book.formatDuration() shouldBe "0m"
        }

        test("formatDuration handles very long duration") {
            val book = createTestBook(duration = 86_400_000L) // 24 hours
            book.formatDuration() shouldBe "24h 0m"
        }

        test("formatDuration truncates partial minutes") {
            // 1 hour 30 minutes 45 seconds - should show 1h 30m (ignoring seconds)
            val book = createTestBook(duration = 5_445_000L)
            book.formatDuration() shouldBe "1h 30m"
        }

        // ========== Cover Path Tests ==========

        test("hasCover returns true when cover path is set") {
            val book = createTestBook(coverPath = "/path/to/cover.jpg")
            book.hasCover shouldBe true
        }

        test("hasCover returns false when cover path is null") {
            val book = createTestBook(coverPath = null)
            book.hasCover shouldBe false
        }

        // ========== Series Title Tests ==========

        test("fullSeriesTitle returns name and sequence when both present") {
            val book = createTestBook(series = listOf(BookSeries(seriesId = "series-1", seriesName = "Mistborn", sequence = "1")))
            book.fullSeriesTitle shouldBe "Mistborn #1"
        }

        test("fullSeriesTitle handles decimal sequence numbers") {
            val book = createTestBook(series = listOf(BookSeries(seriesId = "series-1", seriesName = "Stormlight Archive", sequence = "2.5")))
            book.fullSeriesTitle shouldBe "Stormlight Archive #2.5"
        }

        test("fullSeriesTitle returns only name when sequence is null") {
            val book = createTestBook(series = listOf(BookSeries(seriesId = "series-1", seriesName = "Wheel of Time", sequence = null)))
            book.fullSeriesTitle shouldBe "Wheel of Time"
        }

        test("fullSeriesTitle returns only name when sequence is blank") {
            val book = createTestBook(series = listOf(BookSeries(seriesId = "series-1", seriesName = "Wheel of Time", sequence = "  ")))
            book.fullSeriesTitle shouldBe "Wheel of Time"
        }

        test("fullSeriesTitle returns null when no series") {
            val book = createTestBook(series = emptyList())
            book.fullSeriesTitle.shouldBeNull()
        }

        // ========== Contributor Names Tests ==========

        test("authorNames joins multiple authors with commas") {
            val book =
                createTestBook(
                    authors =
                        listOf(
                            BookContributor("1", "Brandon Sanderson"),
                            BookContributor("2", "Robert Jordan"),
                        ),
                )
            book.authorNames shouldBe "Brandon Sanderson, Robert Jordan"
        }

        test("authorNames returns single author name") {
            val book =
                createTestBook(
                    authors = listOf(BookContributor("1", "Brandon Sanderson")),
                )
            book.authorNames shouldBe "Brandon Sanderson"
        }

        test("authorNames returns empty string when no authors") {
            val book = createTestBook(authors = emptyList())
            book.authorNames shouldBe ""
        }

        test("narratorNames joins multiple narrators with commas") {
            val book =
                createTestBook(
                    narrators =
                        listOf(
                            BookContributor("1", "Michael Kramer"),
                            BookContributor("2", "Kate Reading"),
                        ),
                )
            book.narratorNames shouldBe "Michael Kramer, Kate Reading"
        }

        test("narratorNames returns single narrator name") {
            val book =
                createTestBook(
                    narrators = listOf(BookContributor("1", "Michael Kramer")),
                )
            book.narratorNames shouldBe "Michael Kramer"
        }

        test("narratorNames returns empty string when no narrators") {
            val book = createTestBook(narrators = emptyList())
            book.narratorNames shouldBe ""
        }
    })
