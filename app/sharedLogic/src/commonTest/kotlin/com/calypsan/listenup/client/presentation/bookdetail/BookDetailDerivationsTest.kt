package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.core.SeriesId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private fun at(
    ms: Long,
    finished: Boolean = false,
) = PlaybackPosition(
    bookId = "book-1",
    positionMs = ms,
    playbackSpeed = 1f,
    hasCustomSpeed = false,
    volumeBoostDb = 0f,
    hasCustomBoost = false,
    measuredGainDb = null,
    updatedAtMs = 0L,
    syncedAtMs = null,
    lastPlayedAtMs = 0L,
    isFinished = finished,
)

class BookDetailDerivationsTest :
    FunSpec({
        context("readingStage") {
            test("no saved position is not started") {
                readingStage(position = null, durationMs = 3_600_000L) shouldBe ReadingStage.NotStarted
            }
            test("a position at zero is not started") {
                readingStage(at(0L), 3_600_000L) shouldBe ReadingStage.NotStarted
            }
            test("a partial position is in progress") {
                readingStage(at(900_000L), 3_600_000L) shouldBe ReadingStage.InProgress
            }
            test("the finished flag is finished, wherever the position sits") {
                readingStage(at(0L, finished = true), 3_600_000L) shouldBe ReadingStage.Finished
                readingStage(at(900_000L, finished = true), 3_600_000L) shouldBe ReadingStage.Finished
            }
            test("a book of unknown length never reads as in progress") {
                readingStage(at(900_000L), durationMs = 0L) shouldBe ReadingStage.NotStarted
            }
        }

        context("sectionOrderFor") {
            val all = SectionPresence(hasSeries = true, hasChapters = true)
            test("not started leads with About, Hardcover straight after Ratings") {
                sectionOrderFor(ReadingStage.NotStarted, all) shouldBe
                    listOf(
                        BookDetailSection.About,
                        BookDetailSection.Ratings,
                        BookDetailSection.Hardcover,
                        BookDetailSection.Readers,
                        BookDetailSection.Series,
                        BookDetailSection.Chapters,
                        BookDetailSection.Details,
                    )
            }
            test("in progress leads with Chapters; Series follows About") {
                sectionOrderFor(ReadingStage.InProgress, all) shouldBe
                    listOf(
                        BookDetailSection.Chapters,
                        BookDetailSection.About,
                        BookDetailSection.Series,
                        BookDetailSection.Readers,
                        BookDetailSection.Ratings,
                        BookDetailSection.Hardcover,
                        BookDetailSection.Details,
                    )
            }
            test("finished leads with Ratings (How was it?)") {
                sectionOrderFor(ReadingStage.Finished, all) shouldBe
                    listOf(
                        BookDetailSection.Ratings,
                        BookDetailSection.Hardcover,
                        BookDetailSection.Readers,
                        BookDetailSection.Series,
                        BookDetailSection.Chapters,
                        BookDetailSection.About,
                        BookDetailSection.Details,
                    )
            }
            test("a book in no series and with no chapters skips both, leaving no gap") {
                sectionOrderFor(ReadingStage.InProgress, SectionPresence(hasSeries = false, hasChapters = false)) shouldBe
                    listOf(
                        BookDetailSection.About,
                        BookDetailSection.Readers,
                        BookDetailSection.Ratings,
                        BookDetailSection.Hardcover,
                        BookDetailSection.Details,
                    )
            }
        }

        context("primaryActionFor") {
            test("each stage has its own label when the book can play") {
                primaryActionFor(ReadingStage.NotStarted, showServerWarning = false, canPlay = true) shouldBe PrimaryAction.Play
                primaryActionFor(ReadingStage.InProgress, showServerWarning = false, canPlay = true) shouldBe PrimaryAction.Resume
                primaryActionFor(ReadingStage.Finished, showServerWarning = false, canPlay = true) shouldBe PrimaryAction.ListenAgain
            }
            test("unreachable with nothing on the device is Unavailable offline, whatever the stage") {
                primaryActionFor(ReadingStage.InProgress, showServerWarning = true, canPlay = false) shouldBe
                    PrimaryAction.UnavailableOffline
                primaryActionFor(ReadingStage.Finished, showServerWarning = true, canPlay = false) shouldBe
                    PrimaryAction.UnavailableOffline
            }
            test("unreachable but partly downloaded still resumes from disk") {
                primaryActionFor(ReadingStage.InProgress, showServerWarning = true, canPlay = true) shouldBe PrimaryAction.Resume
            }
            test("a held book (no warning, cannot play) is not Unavailable offline: triage replaces the action") {
                primaryActionFor(ReadingStage.NotStarted, showServerWarning = false, canPlay = false) shouldBe PrimaryAction.Play
            }
        }

        context("currentChapterOf") {
            val chapters = TestData.chapters(count = 4, chapterDuration = 900_000L) // starts 0, 900k, 1.8M, 2.7M
            test("names the chapter the position is in, one-based, with time into it") {
                currentChapterOf(chapters, positionMs = 1_000_000L) shouldBe
                    CurrentChapter(index = 1, number = 2, title = "Chapter 2", startMs = 900_000L, elapsedInChapterMs = 100_000L)
            }
            test("a position exactly on a boundary is in the chapter that starts there") {
                currentChapterOf(chapters, 1_800_000L)?.index shouldBe 2
            }
            test("no chapters, no current chapter") {
                currentChapterOf(emptyList(), 1_000L) shouldBe null
            }
        }

        context("chapterWindow") {
            fun rows(n: Int) = (0 until n).map { ChapterUiModel(id = "c$it", title = "Ch $it", duration = "1m", imageUrl = null) }

            fun ids(window: List<ChapterUiModel>) = window.map { it.id }

            test("no current chapter shows the first five") {
                ids(chapterWindow(rows(20), currentIndex = null)) shouldBe listOf("c0", "c1", "c2", "c3", "c4")
            }
            test("the current chapter sits in the middle, two either side") {
                ids(chapterWindow(rows(20), currentIndex = 10)) shouldBe listOf("c8", "c9", "c10", "c11", "c12")
            }
            test("clamped at the start") {
                ids(chapterWindow(rows(20), currentIndex = 0)) shouldBe listOf("c0", "c1", "c2", "c3", "c4")
                ids(chapterWindow(rows(20), currentIndex = 1)) shouldBe listOf("c0", "c1", "c2", "c3", "c4")
            }
            test("clamped at the end") {
                ids(chapterWindow(rows(20), currentIndex = 19)) shouldBe listOf("c15", "c16", "c17", "c18", "c19")
            }
            test("fewer than five chapters shows them all") {
                ids(chapterWindow(rows(3), currentIndex = 2)) shouldBe listOf("c0", "c1", "c2")
            }
        }

        context("seriesShelfOf") {
            fun series(vararg idsBySequence: Pair<String, Double?>): SeriesWithBooks =
                SeriesWithBooks(
                    series = Series(id = SeriesId("s1"), name = "Mistborn"),
                    // Deliberately out of order: the shelf must sort by sequence.
                    books = idsBySequence.reversed().map { (id, _) -> TestData.bookListItem(id = id, coverPath = "/c/$id.jpg") },
                    bookSequences = idsBySequence.toMap(),
                )

            test("the books in sequence, this one marked, the next named") {
                val shelf = seriesShelfOf(series("b1" to 1.0, "b2" to 2.0, "b3" to 3.0), thisBookId = "b2").shouldNotBeNull()
                shelf.seriesId shouldBe "s1"
                shelf.name shouldBe "Mistborn"
                shelf.books.map { it.id } shouldBe listOf("b1", "b2", "b3")
                shelf.books.map { it.sequenceLabel } shouldBe listOf("1", "2", "3")
                shelf.books.map { it.isThis } shouldBe listOf(false, true, false)
                shelf.thisIndex shouldBe 1
                shelf.nextBookId shouldBe "b3"
            }
            test("the last book has no next") {
                seriesShelfOf(series("b1" to 1.0, "b2" to 2.0), thisBookId = "b2")?.nextBookId shouldBe null
            }
            test("a half-numbered book keeps its half, an unnumbered one goes last with no label") {
                val shelf = seriesShelfOf(series("x" to null, "b1" to 1.0, "b15" to 1.5), thisBookId = "b1").shouldNotBeNull()
                shelf.books.map { it.id } shouldBe listOf("b1", "b15", "x")
                shelf.books.map { it.sequenceLabel } shouldBe listOf("1", "1.5", null)
            }
            test("no series, no shelf") {
                seriesShelfOf(null, thisBookId = "b1") shouldBe null
            }
            test("a series that does not (yet) list this book gives no shelf rather than a wrong one") {
                seriesShelfOf(series("b1" to 1.0), thisBookId = "b9") shouldBe null
            }
        }
    })
