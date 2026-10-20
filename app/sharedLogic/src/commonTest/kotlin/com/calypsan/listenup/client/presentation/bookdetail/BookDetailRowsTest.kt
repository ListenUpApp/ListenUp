package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.AudioFile
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.domain.model.Genre
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Book Detail's Details rows (spec §3.2.1 D31): which rows exist, in which order, with which values. */
class BookDetailRowsTest :
    FunSpec({
        val file =
            AudioFile(id = "a1", index = 0, filename = "disc1/01.m4b", format = "m4b", codec = "aac",
                duration = 3_600_000L, size = 28_800_000L, bitrate = 64_000)
        fun doc(id: String, format: String = "pdf", size: Long = 18L * 1024 * 1024) =
            BookDocument(id = id, index = 0, filename = "extras/$id.$format", format = format, size = size,
                hash = "h".repeat(64))

        val full =
            BookDetailUiState.Ready(
                book =
                    TestData.bookDetail(id = "b1").copy(
                        publisher = "Macmillan Audio",
                        publishYear = 2010,
                        language = "en",
                        isbn = " 9781427209979 ",
                        asin = "B003ZWFO7E",
                        abridged = false,
                        duration = 163_800_000L,
                        genres = listOf(
                            Genre(id = "g1", name = "Fantasy", slug = "fantasy", path = "/fiction/fantasy"),
                            Genre(id = "g2", name = "Epic fantasy", slug = "epic", path = "/fiction/fantasy/epic"),
                        ),
                        allContributors = List(6) { BookContributor(id = "c$it", name = "P$it") },
                        audioFiles = listOf(file),
                    ),
                year = 2010,
                addedAt = 1_770_000_000_000L,
                chapters = List(75) { ChapterUiModel(id = "ch$it", title = "", duration = "", imageUrl = null) },
            )

        test("every row the book carries, in the spec's order") {
            bookDetailRows(full, documents = listOf(doc("map"))) shouldBe
                listOf(
                    BookDetailRow.Publisher("Macmillan Audio"),
                    BookDetailRow.Released(2010),
                    BookDetailRow.GenreLine(genreName = "Epic fantasy", abridged = false),
                    BookDetailRow.Language("English"),
                    BookDetailRow.Length(durationMs = 163_800_000L, chapterCount = 75),
                    BookDetailRow.Added(1_770_000_000_000L),
                    BookDetailRow.Isbn("9781427209979"),
                    BookDetailRow.Asin("B003ZWFO7E"),
                    BookDetailRow.AllCredits(count = 6),
                    BookDetailRow.FilesAndAudio(container = "M4B", bitrate = "64 kbps"),
                    BookDetailRow.MapsAndIllustrations(count = 1, onlyDocumentId = "map", format = "PDF",
                        totalBytes = 18L * 1024 * 1024),
                )
        }

        test("a row whose datum is absent is not drawn") {
            val bare = full.copy(
                book = full.book.copy(publisher = " ", isbn = null, asin = "", genres = emptyList(), language = null),
                year = null,
            )
            bookDetailRows(bare, emptyList()).map { it::class } shouldBe
                listOf(BookDetailRow.Length::class, BookDetailRow.Added::class, BookDetailRow.AllCredits::class,
                    BookDetailRow.FilesAndAudio::class)
        }

        test("credits fall back to the byline's people, each counted once") {
            val book = full.book.copy(
                allContributors = emptyList(),
                authors = listOf(BookContributor(id = "a", name = "A")),
                narrators = listOf(BookContributor(id = "a", name = "A"), BookContributor(id = "n", name = "N")),
            )
            bookDetailRows(full.copy(book = book), emptyList()) shouldContainRow BookDetailRow.AllCredits(2)
        }

        test("several documents open a list: no single id, and a format only when they share one") {
            val rows = bookDetailRows(full, listOf(doc("map"), doc("notes", "epub", 512L)))
            rows.last() shouldBe
                BookDetailRow.MapsAndIllustrations(count = 2, onlyDocumentId = null, format = null,
                    totalBytes = 18L * 1024 * 1024 + 512L)
        }

        test("no audio files, no Files & audio row") {
            bookDetailRows(full.copy(book = full.book.copy(audioFiles = emptyList())), emptyList())
                .none { it is BookDetailRow.FilesAndAudio } shouldBe true
        }
    })

private infix fun List<BookDetailRow>.shouldContainRow(row: BookDetailRow) = (row in this) shouldBe true
