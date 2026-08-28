package com.calypsan.listenup.client.presentation.chaptereditor

import com.calypsan.listenup.client.domain.chapter.ChapterAnchor
import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** 65 hours, 311 chapters — the scale the spec sizes drift correction against. */
private const val BOOK_MS = 234_000_000L
private const val CHAPTER_COUNT = 311

/** An evenly-scraped book: the shape a provider gives you before drift is discovered. */
private fun scrapedBook(count: Int = CHAPTER_COUNT): List<Chapter> {
    val step = BOOK_MS / count
    return List(count) { i ->
        Chapter(id = "c$i", title = "Chapter ${i + 1}", duration = step, startTime = i * step)
    }
}

/**
 * The guided drift flow.
 *
 * This is the editor's answer to bulk error: a scrape whose offset *grows* across the book, where
 * correcting 311 boundaries by hand is not a real option. The user pins two chapters they can hear
 * are right, and everything between is interpolated.
 *
 * The tests that matter are the ones about refusing: a mis-set anchor is the most likely mistake in
 * this flow, and the difference between blocking it and "correcting" a book into nonsense is the
 * whole safety of the feature.
 */
class DriftCorrectionTest :
    FunSpec({

        test("one anchor is a constant shift across the whole book") {
            // The spec's degenerate case, and the thing Audiobookshelf offers as its only tool.
            val book = scrapedBook(10)
            val proposal = DriftProposal(first = ChapterAnchor("c0", trueStartMs = 3_200L))

            val preview = previewDrift(book, proposal, BOOK_MS).shouldBeInstanceOf<DriftPreview.Ready>()

            withClue("every boundary moves by the same amount") {
                preview.firstOffsetMs shouldBe 3_200L
                preview.lastOffsetMs shouldBe 3_200L
                preview.spreadMs shouldBe 0L
            }
        }

        test("TWO ANCHORS CORRECT DRIFT THAT GROWS ACROSS THE BOOK") {
            // The case that actually motivates the feature: 3.2s out at the start, 47.9s by the end.
            val book = scrapedBook()
            val proposal =
                DriftProposal(
                    first = ChapterAnchor("c0", trueStartMs = 3_200L),
                    second = ChapterAnchor("c310", trueStartMs = book.last().startTime + 47_900L),
                )

            val preview = previewDrift(book, proposal, BOOK_MS).shouldBeInstanceOf<DriftPreview.Ready>()

            preview.firstOffsetMs shouldBe 3_200L
            withClue("the far end is corrected by far more than the near end") {
                preview.lastOffsetMs shouldBeGreaterThan 47_000L
                preview.spreadMs shouldBeGreaterThan 40_000L
            }
            withClue("all 311 boundaries are corrected from two pinned points") {
                preview.affectedCount shouldBe CHAPTER_COUNT
            }
        }

        test("the corrected set stays ordered and contiguous") {
            val book = scrapedBook(50)
            val proposal =
                DriftProposal(
                    first = ChapterAnchor("c0", trueStartMs = 2_000L),
                    second = ChapterAnchor("c49", trueStartMs = book.last().startTime + 30_000L),
                )

            val corrected =
                previewDrift(book, proposal, BOOK_MS)
                    .shouldBeInstanceOf<DriftPreview.Ready>()
                    .corrected

            withClue("boundaries never cross") {
                corrected.map { it.startTime } shouldBe corrected.map { it.startTime }.sorted()
            }
            withClue("each chapter still ends where the next begins") {
                corrected.zipWithNext().forEach { (a, b) -> a.startTime + a.duration shouldBe b.startTime }
            }
        }

        test("ANCHORS THAT RUN BACKWARDS ARE REFUSED, NOT APPLIED") {
            // Audio does not play in reverse, so this is always a mis-set anchor rather than real
            // drift. "Correcting" it would rewrite the book into nonsense from a single mistyped time.
            val book = scrapedBook(10)
            val proposal =
                DriftProposal(
                    first = ChapterAnchor("c0", trueStartMs = 100_000L),
                    second = ChapterAnchor("c9", trueStartMs = 0L),
                )

            previewDrift(book, proposal, BOOK_MS)
                .shouldBeInstanceOf<DriftPreview.Refused>()
                .reason shouldBe DriftRefusal.InvertedAnchors
        }

        test("an anchor naming a chapter that is not there is refused") {
            val proposal = DriftProposal(first = ChapterAnchor("nope", trueStartMs = 1_000L))

            previewDrift(scrapedBook(5), proposal, BOOK_MS)
                .shouldBeInstanceOf<DriftPreview.Refused>()
                .reason shouldBe DriftRefusal.UnusableAnchors
        }

        test("both anchors on the same chapter is refused rather than dividing by zero") {
            val proposal =
                DriftProposal(
                    first = ChapterAnchor("c1", trueStartMs = 1_000L),
                    second = ChapterAnchor("c1", trueStartMs = 9_000L),
                )

            previewDrift(scrapedBook(5), proposal, BOOK_MS)
                .shouldBeInstanceOf<DriftPreview.Refused>()
                .reason shouldBe DriftRefusal.UnusableAnchors
        }

        test("LOCKED CHAPTERS KEEP THEIR START AND ARE NOT COUNTED AS MOVED") {
            // The manual override the spec requires: a boundary someone already placed by ear must
            // survive a bulk correction, and the summary must not claim to have moved it.
            val book = scrapedBook(10)
            val proposal =
                DriftProposal(
                    first = ChapterAnchor("c0", trueStartMs = 5_000L),
                    second = ChapterAnchor("c9", trueStartMs = book.last().startTime + 40_000L),
                    lockedIds = setOf("c5"),
                )

            val preview = previewDrift(book, proposal, BOOK_MS).shouldBeInstanceOf<DriftPreview.Ready>()

            preview.corrected.single { it.id == "c5" }.startTime shouldBe
                book.single { it.id == "c5" }.startTime
            withClue("nine moved, one was exempt") { preview.affectedCount shouldBe 9 }
        }

        test("the preview is the set that gets applied — one computation, not two") {
            // The ghosts the user approves and the boundaries that land must come from the same
            // call. A second code path for "apply" is how a preview starts quietly lying.
            val book = scrapedBook(20)
            val proposal =
                DriftProposal(
                    first = ChapterAnchor("c0", trueStartMs = 1_500L),
                    second = ChapterAnchor("c19", trueStartMs = book.last().startTime + 25_000L),
                )

            val once = previewDrift(book, proposal, BOOK_MS).shouldBeInstanceOf<DriftPreview.Ready>()
            val twice = previewDrift(book, proposal, BOOK_MS).shouldBeInstanceOf<DriftPreview.Ready>()

            once.corrected shouldBe twice.corrected
        }

        test("a correction that would leave the book is clamped inside it") {
            val book = scrapedBook(10)
            val proposal = DriftProposal(first = ChapterAnchor("c0", trueStartMs = -50_000L))

            val corrected =
                previewDrift(book, proposal, BOOK_MS)
                    .shouldBeInstanceOf<DriftPreview.Ready>()
                    .corrected

            corrected.first().startTime shouldBe 0L
            corrected.all { it.startTime in 0L..BOOK_MS } shouldBe true
        }

        test("an empty book has nothing to correct and does not fail trying") {
            val proposal = DriftProposal(first = ChapterAnchor("c0", trueStartMs = 1_000L))

            previewDrift(emptyList(), proposal, BOOK_MS)
                .shouldBeInstanceOf<DriftPreview.Ready>()
                .affectedCount shouldBe 0
        }
    })
