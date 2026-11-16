package com.calypsan.listenup.client.domain.chapter

import com.calypsan.listenup.client.domain.chapter.ChapterOutlineRow.BookHeader
import com.calypsan.listenup.client.domain.chapter.ChapterOutlineRow.ChapterRow
import com.calypsan.listenup.client.domain.chapter.ChapterOutlineRow.PartHeader
import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val SPAN_MS = 100L

/** Consecutive chapters [SPAN_MS] long, in the order given, so every span and start is arithmetic. */
private fun book(vararg chapters: Chapter): List<Chapter> =
    chapters.mapIndexed { i, chapter -> chapter.copy(startTime = i * SPAN_MS, duration = SPAN_MS) }

private fun chapter(
    id: String,
    title: String = id,
    part: String? = null,
    bookTitle: String? = null,
) = Chapter(id = id, title = title, duration = 0L, startTime = 0L, partTitle = part, bookTitle = bookTitle)

private fun List<ChapterOutlineRow>.names(): List<String> =
    map { row ->
        when (row) {
            is ChapterRow -> "${row.number} ${row.chapter.id}"
            is PartHeader -> "[${row.title}]"
            is BookHeader -> "{${row.title}}"
        }
    }

/**
 * [toChapterOutline]: the one flat list every chapter surface renders.
 *
 * Grouping is [groupChapters]' job; what this adds is what a reader is shown: every chapter the
 * audiobook has as a row of its own, a divider's count and span, and the number each chapter carries.
 */
class ChapterOutlineTest :
    FunSpec({

        test("an empty book has an empty outline") {
            emptyList<Chapter>().toChapterOutline().shouldBeEmpty()
        }

        test("a flat book is only chapter rows, numbered from one") {
            val chapters = book(chapter("a", title = "Part I"), chapter("b"))

            chapters.toChapterOutline() shouldBe listOf(ChapterRow(chapters[0], 0, 1), ChapterRow(chapters[1], 1, 2))
        }

        test("a part's divider comes before its chapters and counts and spans every one of them") {
            val chapters = book(chapter("a", part = "Part One"), chapter("b"), chapter("c"))

            chapters.toChapterOutline().first() shouldBe
                PartHeader(
                    title = "Part One",
                    chapterId = "a",
                    chapterCount = 3,
                    durationMs = 3 * SPAN_MS,
                    startMs = 0L,
                )
        }

        test("a chapter named for the part it opens is an ordinary row, counted like any other") {
            val chapters = book(chapter("a", title = "Part I", part = "Part I"), chapter("b"), chapter("c"))

            val rows = chapters.toChapterOutline()

            withClue("the audiobook's own chapter stays listed, as the audiobook has it") {
                rows.names() shouldBe listOf("[Part I]", "1 a", "2 b", "3 c")
            }
            rows.first().shouldBeInstanceOf<PartHeader>().chapterCount shouldBe 3
        }

        test("chapters before the first divider stand on their own, above it") {
            val chapters = book(chapter("intro"), chapter("a", part = "Part One"), chapter("b"))

            val rows = chapters.toChapterOutline()

            rows[0] shouldBe ChapterRow(chapters[0], 0, 1)
            rows[1].shouldBeInstanceOf<PartHeader>().startMs shouldBe SPAN_MS
        }

        test("a chapter opening both tiers yields the book heading, then the part divider") {
            val chapters = book(chapter("a", part = "Part One", bookTitle = "Book One"), chapter("b"))

            val rows = chapters.toChapterOutline()

            rows[0] shouldBe BookHeader(title = "Book One", chapterId = "a", startMs = 0L)
            rows[1].shouldBeInstanceOf<PartHeader>().title shouldBe "Part One"
        }

        test("a book may hold chapters with no part, then a part, without losing either") {
            val chapters =
                book(chapter("a", bookTitle = "Book One"), chapter("b"), chapter("c", part = "Part One"))

            chapters.toChapterOutline().map { it::class } shouldBe
                listOf(BookHeader::class, ChapterRow::class, ChapterRow::class, PartHeader::class, ChapterRow::class)
        }

        // ── numbering ─────────────────────────────────────────────────────────────

        test("numbering runs on across parts, in audio order") {
            val chapters =
                book(
                    chapter("credits"),
                    chapter("p1", title = "Part I", part = "Part I"),
                    chapter("a"),
                    chapter("p2", title = "Part II", part = "Part II"),
                    chapter("b"),
                )

            chapters.toChapterOutline().names() shouldBe
                listOf("1 credits", "[Part I]", "2 p1", "3 a", "[Part II]", "4 p2", "5 b")
        }

        test("an omnibus restarts at one at each book, and runs on across that book's parts") {
            val chapters =
                book(
                    chapter("r1", bookTitle = "Red Rising", part = "Part I"),
                    chapter("r2"),
                    chapter("r3", part = "Part II"),
                    chapter("g1", bookTitle = "Golden Son"),
                    chapter("g2", part = "Part I"),
                    chapter("g3"),
                )

            chapters.toChapterOutline().names() shouldBe
                listOf(
                    "{Red Rising}",
                    "[Part I]",
                    "1 r1",
                    "2 r2",
                    "[Part II]",
                    "3 r3",
                    "{Golden Son}",
                    "1 g1",
                    "[Part I]",
                    "2 g2",
                    "3 g3",
                )
        }

        test("chapters before an omnibus's first book count from one, and the first book starts again") {
            val chapters = book(chapter("intro"), chapter("a", bookTitle = "Book One"), chapter("b"))

            chapters.toChapterOutline().names() shouldBe listOf("1 intro", "{Book One}", "1 a", "2 b")
        }

        test("a row's index is its place in the whole list, whatever number it carries") {
            val chapters = book(chapter("a", bookTitle = "One"), chapter("b"), chapter("c", bookTitle = "Two"))

            chapters.toChapterOutline().filterIsInstance<ChapterRow>().map { it.index to it.number } shouldBe
                listOf(0 to 1, 1 to 2, 2 to 1)
        }
    })
