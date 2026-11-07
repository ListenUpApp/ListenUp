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
 * outline. What these pin is the window staying the same five chapters, the part being named when
 * its header is out of view, and the next part's header showing when it falls inside.
 */
class GroupedChaptersTest :
    FunSpec({

        test("a book with no markers has no grouped chapters") {
            book("a", "b", "c").groupedChapters(currentIndex = 1) shouldBe null
        }

        test("the window names the part it starts inside") {
            val grouped =
                book("Part I", "1", "2", "3", "4", "5", "6", "7").groupedChapters(currentIndex = 4).shouldNotBeNull()

            grouped.previewLabel shouldBe "Part I"
            grouped.previewRows.names() shouldBe listOf("2", "3", "4", "5", "6")
        }

        test("the next part's header shows where it falls in the window, in place of its folded chapter") {
            val grouped =
                book("Part I", "1", "2", "3", "4", "Part II", "5", "6")
                    .groupedChapters(currentIndex = 3)
                    .shouldNotBeNull()

            grouped.previewLabel shouldBe "Part I"
            grouped.previewRows.names() shouldBe listOf("1", "2", "3", "4", "[Part II]")
        }

        test("a window opening on a header needs no label: the header says it") {
            val grouped = book("Part I", "1", "2", "3", "4", "5", "6").groupedChapters(currentIndex = null).shouldNotBeNull()

            grouped.previewLabel shouldBe null
            grouped.previewRows.names() shouldBe listOf("[Part I]", "1", "2", "3", "4")
        }

        test("chapters before the first part are named by nothing") {
            val grouped = book("intro", "a", "b", "Part I", "1", "2", "3").groupedChapters(currentIndex = 1).shouldNotBeNull()

            grouped.previewLabel shouldBe null
            grouped.previewRows.names() shouldBe listOf("intro", "a", "b", "[Part I]", "1")
        }

        test("a window inside a book with no parts is named by the book") {
            val chapters =
                book("a", "b", "c", "d", "e", "f", "g", "h").mapIndexed { i, chapter ->
                    if (i == 0) chapter.copy(bookTitle = "Book One") else chapter
                }

            chapters.groupedChapters(currentIndex = 5).shouldNotBeNull().previewLabel shouldBe "Book One"
        }

        test("a part inside a book is named by the part, not the book") {
            val chapters =
                book("a", "Part I", "c", "d", "e", "f", "g", "h").mapIndexed { i, chapter ->
                    if (i == 0) chapter.copy(bookTitle = "Book One") else chapter
                }

            chapters.groupedChapters(currentIndex = 5).shouldNotBeNull().previewLabel shouldBe "Part I"
        }

        test("the window is the plain list's window: a fold never pulls in a sixth chapter") {
            val chapters = book("Part I", "1", "2", "3", "4", "Part II", "5", "6", "7", "8")

            val rows = chapters.groupedChapters(currentIndex = 5).shouldNotBeNull().previewRows

            withClue("five chapters, the fold among them: four rows and the header, not five rows") {
                rows.names() shouldBe listOf("3", "4", "[Part II]", "5", "6")
            }
        }

        test("the whole outline folds its announcements, and the current chapter reaches it") {
            val grouped = book("Part I", "1", "Part II", "2").groupedChapters(currentIndex = 2).shouldNotBeNull()

            grouped.rows.names() shouldBe listOf("[Part I]", "1", "[Part II]", "2")
            grouped.rows
                .filterIsInstance<PartHeader>()
                .map { it.isCurrentChapter } shouldBe listOf(false, true)
        }
    })
