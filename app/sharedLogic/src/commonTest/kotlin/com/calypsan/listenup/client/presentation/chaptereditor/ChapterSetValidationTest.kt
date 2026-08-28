package com.calypsan.listenup.client.presentation.chaptereditor

import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val BOOK_MS = 1_200_000L

private fun set(vararg chapters: Chapter) = chapters.toList()

private fun chapter(
    id: String,
    startMs: Long,
    title: String = "Chapter $id",
) = Chapter(id = id, title = title, duration = 0L, startTime = startMs)

/**
 * [validateForSave] — the last check before a chapter set leaves the client.
 *
 * The edit operations already keep the set valid on their own. This exists because they are not
 * the only way it changes: drift correction rewrites every boundary at once, and an editor that
 * trusted only its own operations would have nothing to say when that produced something
 * impossible.
 *
 * It also stands between the user and a crash. `ChapterInput` validates in its `init`, so mapping
 * an invalid set onto the wire types throws — inside the save coroutine, where it surfaces as a
 * save that simply vanished. Every case below is one that would otherwise do that.
 */
class ChapterSetValidationTest :
    FunSpec({

        test("a well-formed set has nothing to report") {
            set(chapter("a", 0L), chapter("b", 300_000L), chapter("c", 900_000L))
                .validateForSave(BOOK_MS)
                .shouldBeEmpty()
        }

        test("an empty set is valid — a book with no chapters is a state, not an error") {
            emptyList<Chapter>().validateForSave(BOOK_MS).shouldBeEmpty()
        }

        test("a blank title is caught before ChapterInput can throw on it") {
            val problems = set(chapter("a", 0L, title = "   ")).validateForSave(BOOK_MS)

            problems.single().shouldBeInstanceOf<ChapterSetProblem.BlankTitle>().chapterId shouldBe "a"
        }

        test("a title past the server's ceiling is caught here rather than refused on arrival") {
            val problems =
                set(chapter("a", 0L, title = "x".repeat(ChapterInput.MAX_TITLE + 1))).validateForSave(BOOK_MS)

            problems.single().shouldBeInstanceOf<ChapterSetProblem.TitleTooLong>().length shouldBe
                ChapterInput.MAX_TITLE + 1
        }

        test("two boundaries at the same instant are rejected") {
            val problems =
                set(chapter("a", 0L), chapter("b", 300_000L), chapter("c", 300_000L))
                    .validateForSave(BOOK_MS)

            problems.single().shouldBeInstanceOf<ChapterSetProblem.NotStrictlyIncreasing>().chapterId shouldBe "c"
        }

        test("ORDER IS CHECKED AS HELD, NOT AS IT WOULD SORT") {
            // A set that only becomes valid after sorting is still the wrong set: what gets sent is
            // the order the list is in. Sorting here would hide the bug and send something the user
            // never saw.
            val problems =
                set(chapter("a", 0L), chapter("b", 900_000L), chapter("c", 300_000L))
                    .validateForSave(BOOK_MS)

            problems.single().shouldBeInstanceOf<ChapterSetProblem.NotStrictlyIncreasing>().chapterId shouldBe "c"
        }

        test("a boundary before the start of the audio is rejected") {
            val problems = set(chapter("a", -1L)).validateForSave(BOOK_MS)

            problems.single().shouldBeInstanceOf<ChapterSetProblem.OutsideBook>().startMs shouldBe -1L
        }

        test("a boundary at or past the end of the audio is rejected") {
            // At the end, not merely past it: a chapter starting on the final millisecond has no
            // audio in it, and the derived duration would be zero.
            set(chapter("a", 0L), chapter("b", BOOK_MS)).validateForSave(BOOK_MS) shouldHaveSize 1
            set(chapter("a", 0L), chapter("b", BOOK_MS + 1L)).validateForSave(BOOK_MS) shouldHaveSize 1
        }

        test("a leading gap is allowed — the publisher intro nobody wants inside chapter one") {
            withClue("the spec permits a gap before the first chapter, and only there") {
                set(chapter("a", 30_000L), chapter("b", 300_000L)).validateForSave(BOOK_MS).shouldBeEmpty()
            }
        }

        test("every problem is reported, not just the first") {
            // A save button that re-fails on a different row each time you press it is worse than
            // one that tells you everything wrong at once.
            val problems =
                set(
                    chapter("a", 0L, title = ""),
                    chapter("b", -5L),
                    chapter("c", -10L),
                ).validateForSave(BOOK_MS)

            // Five, not three: a boundary at a negative time is BOTH outside the book and out of
            // order, and each is separately worth telling the user about — fixing one does not
            // necessarily fix the other.
            problems.size shouldBe 5
            problems.map { it.chapterId } shouldBe listOf("a", "b", "b", "c", "c")
        }

        test("a drift result that inverts the book is caught") {
            // The realistic path to an invalid set: correction is applied with mis-set anchors and
            // rewrites all 311 boundaries at once. No single edit operation was involved.
            val drifted = set(chapter("a", 900_000L), chapter("b", 300_000L), chapter("c", 0L))

            drifted.validateForSave(BOOK_MS) shouldHaveSize 2
        }
    })
