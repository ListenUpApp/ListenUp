package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.chapter.ChapterOutlineRow
import com.calypsan.listenup.client.domain.chapter.ChapterOutlineRow.BookHeader
import com.calypsan.listenup.client.domain.chapter.ChapterOutlineRow.ChapterRow
import com.calypsan.listenup.client.domain.chapter.ChapterOutlineRow.PartHeader
import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private const val SPAN_MS = 100L

/** Chapters named by id; a `Part x` id both titles the chapter and opens a part of that name. */
private fun book(vararg ids: String): List<Chapter> =
    ids.mapIndexed { i, id ->
        Chapter(
            id = id,
            title = id,
            duration = SPAN_MS,
            startTime = i * SPAN_MS,
            partTitle = id.takeIf { it.startsWith("Part") },
        )
    }

private fun List<Chapter>.opensBook(
    index: Int,
    title: String,
): List<Chapter> = mapIndexed { i, chapter -> if (i == index) chapter.copy(bookTitle = title) else chapter }

private fun List<ChapterOutlineRow>.names(): List<String> =
    map { row ->
        when (row) {
            is ChapterRow -> row.chapter.id
            is PartHeader -> "[${row.title}]"
            is BookHeader -> "{${row.title}}"
        }
    }

/**
 * The collapsed list of a marked book: the five-chapter window every book has, seen through the
 * outline. What these pin is the window staying the same five chapters, the divider of the part it
 * starts inside heading it, and a divider showing wherever the window crosses into another part.
 */
class GroupedChaptersTest :
    FunSpec({

        test("a book with no markers has no grouped chapters") {
            book("a", "b", "c").groupedChapters(currentIndex = 1) shouldBe null
        }

        test("a window starting inside a part is headed by that part's divider") {
            val grouped =
                book("Part I", "1", "2", "3", "4", "5", "6", "7").groupedChapters(currentIndex = 4).shouldNotBeNull()

            grouped.previewRows.names() shouldBe listOf("[Part I]", "2", "3", "4", "5", "6")
        }

        test("crossing into the next part puts its divider between the two parts' rows") {
            val grouped =
                book("Part I", "1", "2", "3", "4", "Part II", "5", "6")
                    .groupedChapters(currentIndex = 3)
                    .shouldNotBeNull()

            withClue("the chapter that opens Part II is a row of its own under the divider") {
                grouped.previewRows.names() shouldBe listOf("[Part I]", "1", "2", "3", "4", "[Part II]", "Part II")
            }
        }

        test("a window opening on a part's first chapter has that divider once") {
            val grouped = book("Part I", "1", "2", "3", "4", "5", "6").groupedChapters(currentIndex = null).shouldNotBeNull()

            grouped.previewRows.names() shouldBe listOf("[Part I]", "Part I", "1", "2", "3", "4")
        }

        test("chapters before the first part have no divider above them") {
            val grouped = book("intro", "a", "b", "Part I", "1", "2", "3").groupedChapters(currentIndex = 1).shouldNotBeNull()

            grouped.previewRows.names() shouldBe listOf("intro", "a", "b", "[Part I]", "Part I", "1")
        }

        test("a window inside a book with no parts is headed by the book") {
            val chapters = book("a", "b", "c", "d", "e", "f", "g", "h").opensBook(0, "Book One")

            chapters
                .groupedChapters(currentIndex = 5)
                .shouldNotBeNull()
                .previewRows
                .names() shouldBe
                listOf("{Book One}", "d", "e", "f", "g", "h")
        }

        test("a window inside a part of a book is headed by the book, then the part") {
            val chapters = book("a", "Part I", "c", "d", "e", "f", "g", "h").opensBook(0, "Book One")

            chapters
                .groupedChapters(currentIndex = 5)
                .shouldNotBeNull()
                .previewRows
                .names() shouldBe
                listOf("{Book One}", "[Part I]", "d", "e", "f", "g", "h")
        }

        test("a window crossing into the next book shows its heading, and the old part's divider stays behind") {
            val chapters =
                book("Part I", "1", "2", "3", "Part II", "4", "5", "6")
                    .opensBook(0, "Book One")
                    .opensBook(4, "Book Two")

            chapters
                .groupedChapters(currentIndex = 4)
                .shouldNotBeNull()
                .previewRows
                .names() shouldBe
                listOf("{Book One}", "[Part I]", "2", "3", "{Book Two}", "[Part II]", "Part II", "4", "5")
        }

        test("the window is always the plain list's five chapters: dividers never take a row's place") {
            val chapters = book("Part I", "1", "2", "3", "4", "Part II", "5", "6", "7", "8")

            val rows = chapters.groupedChapters(currentIndex = 5).shouldNotBeNull().previewRows

            rows.filterIsInstance<ChapterRow>().map { it.chapter.id } shouldBe
                chapterWindow(chapters, currentIndex = 5).map { it.id }
        }

        test("the whole outline lists every chapter under its divider") {
            val grouped = book("Part I", "1", "Part II", "2").groupedChapters(currentIndex = 2).shouldNotBeNull()

            grouped.rows.names() shouldBe listOf("[Part I]", "Part I", "1", "[Part II]", "Part II", "2")
        }
    })
