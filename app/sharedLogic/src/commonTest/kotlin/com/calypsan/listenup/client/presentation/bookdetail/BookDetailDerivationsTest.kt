package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.model.PlaybackPosition
import io.kotest.core.spec.style.FunSpec
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
    })
