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

/**
 * [toChapterOutline] — the one flat list every chapter surface renders.
 *
 * Grouping is [groupChapters]' job; what this adds is what a *reader* is shown: a header's count and
 * span, whether it holds the chapter playing, and the announcement chapter that would otherwise say
 * the part's name twice in a row.
 */
class ChapterOutlineTest :
    FunSpec({

        test("an empty book has an empty outline") {
            emptyList<Chapter>().toChapterOutline().shouldBeEmpty()
        }

        test("a flat book is only chapter rows, numbered from one — no header, nothing folded") {
            val chapters = book(chapter("a", title = "Part I"), chapter("b"))

            chapters.toChapterOutline() shouldBe listOf(ChapterRow(chapters[0], 1), ChapterRow(chapters[1], 2))
        }

        test("a part's header comes before its chapters and counts and spans them") {
            val chapters = book(chapter("a", part = "Part One"), chapter("b"), chapter("c"))

            chapters.toChapterOutline().first() shouldBe
                PartHeader(
                    title = "Part One",
                    chapterId = "a",
                    chapterCount = 3,
                    durationMs = 3 * SPAN_MS,
                    startMs = 0L,
                    containsCurrent = false,
                )
        }

        test("chapters before the first header stand on their own, above it") {
            val chapters = book(chapter("intro"), chapter("a", part = "Part One"), chapter("b"))

            val rows = chapters.toChapterOutline()

            rows[0] shouldBe ChapterRow(chapters[0], 1)
            rows[1].shouldBeInstanceOf<PartHeader>().startMs shouldBe SPAN_MS
        }

        test("a chapter opening both tiers yields the book header, then the part header") {
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

        test("containsCurrent is true only on the part holding the current chapter") {
            val chapters =
                book(chapter("a", part = "One"), chapter("b"), chapter("c", part = "Two"), chapter("d"))

            val parts = chapters.toChapterOutline(currentChapterId = "d").filterIsInstance<PartHeader>()

            parts.map { it.containsCurrent } shouldBe listOf(false, true)
        }

        // ── folding the announcement ──────────────────────────────────────────────

        test("an announcement chapter named for its part folds into the header") {
            val chapters =
                book(chapter("a", title = "Part I", part = "Part I"), chapter("b"), chapter("c"))

            val rows = chapters.toChapterOutline()

            withClue("the header takes the announcement's place, so the reader is not told twice") {
                rows.filterIsInstance<ChapterRow>().map { it.chapter.id } shouldBe listOf("b", "c")
            }
            withClue("its play action still starts that chapter") {
                rows.first().shouldBeInstanceOf<PartHeader>().chapterId shouldBe "a"
                rows.first().shouldBeInstanceOf<PartHeader>().startMs shouldBe 0L
            }
        }

        test("folding ignores case and surrounding space") {
            val chapters = book(chapter("a", title = "  PART one ", part = "Part One"), chapter("b"))

            chapters.toChapterOutline().filterIsInstance<ChapterRow>().map { it.chapter.id } shouldBe listOf("b")
        }

        test("a fold takes the chapter out of the count but never out of the span") {
            val chapters = book(chapter("a", title = "Part I", part = "Part I"), chapter("b"), chapter("c"))

            val header = chapters.toChapterOutline().first().shouldBeInstanceOf<PartHeader>()

            header.chapterCount shouldBe 2
            header.durationMs shouldBe 3 * SPAN_MS
        }

        test("a fold renumbers nothing — the rows after it keep the numbers they carry in the book") {
            val chapters =
                book(chapter("intro"), chapter("a", title = "Part I", part = "Part I"), chapter("b"))

            chapters.toChapterOutline().filterIsInstance<ChapterRow>().map { it.number } shouldBe listOf(1, 3)
        }

        test("a folded chapter being the current one still lights its part") {
            val chapters =
                book(chapter("a", title = "Part I", part = "Part I"), chapter("b", title = "Part II", part = "Part II"))

            val parts = chapters.toChapterOutline(currentChapterId = "a").filterIsInstance<PartHeader>()

            parts.map { it.containsCurrent } shouldBe listOf(true, false)
        }

        test("a chapter whose title merely resembles its part is not folded") {
            val chapters = book(chapter("a", title = "Part I: Dawn", part = "Part I"), chapter("b"))

            chapters.toChapterOutline().filterIsInstance<ChapterRow>().map { it.chapter.id } shouldBe listOf("a", "b")
        }

        test("only the opening chapter folds, not a later one that shares the name") {
            val chapters =
                book(chapter("a", part = "Dawn"), chapter("b", title = "Dawn"))

            chapters.toChapterOutline().filterIsInstance<ChapterRow>().map { it.chapter.id } shouldBe listOf("a", "b")
        }

        test("the editor keeps every chapter: Keep shows what Fold would hide, with the same header") {
            val chapters = book(chapter("a", title = "Part I", part = "Part I"), chapter("b"))

            val rows = chapters.toChapterOutline(announcements = AnnouncementChapters.Keep)

            rows.filterIsInstance<ChapterRow>().map { it.chapter.id } shouldBe listOf("a", "b")
            rows.first().shouldBeInstanceOf<PartHeader>().chapterCount shouldBe 2
        }
    })
