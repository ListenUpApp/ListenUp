package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.playback.TimelineFileInput
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun chapter(
    id: String,
    title: String,
    startTime: Long,
    duration: Long,
) = Chapter(id = id, title = title, duration = duration, startTime = startTime)

/**
 * Pure chapter-window-math tests for [currentChapterWindow], [seekTargetToBookPosition],
 * [previousChapterTarget], and [nextChapterTarget].
 *
 * The Media3-aware companion [ChapterWindowPlayer] is exercised on a real device (its
 * constructor requires a live `Looper`, unavailable in this Robolectric-free host-test
 * lane — see the note on [ChapterWindowPlayer] itself); all the actual chapter-window
 * logic lives here so it can be verified without that runtime, mirroring the
 * [controllerTrustOf] / [ControllerGatingTest] split.
 */
class ChapterWindowTest :
    FunSpec({

        val threeChapters =
            listOf(
                chapter("c1", "Chapter One", startTime = 0L, duration = 100_000L),
                chapter("c2", "Chapter Two", startTime = 100_000L, duration = 150_000L),
                chapter("c3", "Chapter Three", startTime = 250_000L, duration = 50_000L),
            )
        val totalDurationMs = 300_000L

        context("currentChapterWindow") {
            test("position at the very start of the book resolves to chapter 0") {
                val window = currentChapterWindow(threeChapters, bookPositionMs = 0L, totalDurationMs)
                window.chapterIndex shouldBe 0
                window.windowStartMs shouldBe 0L
                window.windowDurationMs shouldBe 100_000L
                window.positionInWindowMs shouldBe 0L
            }

            test("mid-chapter position resolves relative position within that chapter") {
                val window = currentChapterWindow(threeChapters, bookPositionMs = 150_000L, totalDurationMs)
                window.chapterIndex shouldBe 1
                window.windowStartMs shouldBe 100_000L
                window.windowDurationMs shouldBe 150_000L
                window.positionInWindowMs shouldBe 50_000L
            }

            test("position exactly on a chapter boundary resolves to the new chapter, not the old one") {
                val window = currentChapterWindow(threeChapters, bookPositionMs = 100_000L, totalDurationMs)
                window.chapterIndex shouldBe 1
                window.positionInWindowMs shouldBe 0L
            }

            test("last chapter's window duration runs to the end of the book") {
                val window = currentChapterWindow(threeChapters, bookPositionMs = 260_000L, totalDurationMs)
                window.chapterIndex shouldBe 2
                window.windowStartMs shouldBe 250_000L
                window.windowDurationMs shouldBe 50_000L
                window.positionInWindowMs shouldBe 10_000L
            }

            test("position past the last chapter's end clamps into that chapter's window") {
                val window = currentChapterWindow(threeChapters, bookPositionMs = 500_000L, totalDurationMs)
                window.chapterIndex shouldBe 2
                window.positionInWindowMs shouldBe window.windowDurationMs
            }

            test("position before the first chapter's start (non-zero first chapter) clamps to window start") {
                val chapters = listOf(chapter("c1", "Intro skipped", startTime = 5_000L, duration = 95_000L))
                val window = currentChapterWindow(chapters, bookPositionMs = 0L, totalBookDurationMs = 100_000L)
                window.chapterIndex shouldBe 0
                window.positionInWindowMs shouldBe 0L
            }

            test("chapterless book presents the whole book as a single window") {
                val window = currentChapterWindow(emptyList(), bookPositionMs = 42_000L, totalDurationMs)
                window.chapterIndex shouldBe -1
                window.windowStartMs shouldBe 0L
                window.windowDurationMs shouldBe totalDurationMs
                window.positionInWindowMs shouldBe 42_000L
            }
        }

        context("seekTargetToBookPosition") {
            test("in-window seek maps directly to a book position") {
                val window = currentChapterWindow(threeChapters, bookPositionMs = 150_000L, totalDurationMs)
                window.seekTargetToBookPosition(30_000L) shouldBe 130_000L
            }

            test("out-of-range seek clamps to the window bounds") {
                val window = currentChapterWindow(threeChapters, bookPositionMs = 150_000L, totalDurationMs)
                window.seekTargetToBookPosition(-10_000L) shouldBe 100_000L
                window.seekTargetToBookPosition(999_000L) shouldBe 250_000L
            }

            test("round-trips through a real PlaybackTimeline across a file boundary") {
                val timeline =
                    PlaybackTimeline.build(
                        bookId = BookId("book-1"),
                        files =
                            listOf(
                                TimelineFileInput(
                                    audioFileId = "af-1",
                                    filename = "part1.mp3",
                                    format = "mp3",
                                    durationMs = 180_000L,
                                    size = 1_000L,
                                    localPath = "/tmp/part1.mp3",
                                    streamingUrl = "",
                                ),
                                TimelineFileInput(
                                    audioFileId = "af-2",
                                    filename = "part2.mp3",
                                    format = "mp3",
                                    durationMs = 180_000L,
                                    size = 1_000L,
                                    localPath = "/tmp/part2.mp3",
                                    streamingUrl = "",
                                ),
                            ),
                    )
                // Chapter 2 spans the file boundary: file 0 ends at 180_000, chapter starts at 150_000.
                val chapters =
                    listOf(
                        chapter("c1", "Chapter One", startTime = 0L, duration = 150_000L),
                        chapter("c2", "Chapter Two", startTime = 150_000L, duration = 100_000L),
                    )

                // Currently 20s into chapter 2, which itself started 30s before the file boundary —
                // so this is 50s into file 1 (index 1).
                val bookPositionMs = timeline.toBookPosition(mediaItemIndex = 1, positionInFileMs = 50_000L)
                bookPositionMs shouldBe 230_000L

                val window = currentChapterWindow(chapters, bookPositionMs, timeline.totalDurationMs)
                window.chapterIndex shouldBe 1
                window.positionInWindowMs shouldBe 80_000L

                // Seek to 10s into chapter 2 — should land 20s before the file boundary, still file 0.
                val seekBookPositionMs = window.seekTargetToBookPosition(10_000L)
                seekBookPositionMs shouldBe 160_000L
                val resolved = timeline.resolve(seekBookPositionMs)
                resolved.mediaItemIndex shouldBe 0
                resolved.positionInFileMs shouldBe 160_000L

                // Seek to 50s into chapter 2 — past the file boundary, into file 1.
                val seekIntoFile2Ms = window.seekTargetToBookPosition(50_000L)
                seekIntoFile2Ms shouldBe 200_000L
                val resolvedFile2 = timeline.resolve(seekIntoFile2Ms)
                resolvedFile2.mediaItemIndex shouldBe 1
                resolvedFile2.positionInFileMs shouldBe 20_000L
            }
        }

        context("previousChapterTarget") {
            test("early in the current chapter, previous moves to the start of the prior chapter") {
                val target = previousChapterTarget(threeChapters, bookPositionMs = 101_000L, totalDurationMs)
                target shouldBe 0L
            }

            test("more than the restart threshold into the chapter, previous restarts the current chapter") {
                val target = previousChapterTarget(threeChapters, bookPositionMs = 120_000L, totalDurationMs)
                target shouldBe 100_000L
            }

            test("exactly at the restart threshold still moves to the prior chapter") {
                val target =
                    previousChapterTarget(
                        threeChapters,
                        bookPositionMs = 100_000L + 3_000L,
                        totalDurationMs,
                        restartThresholdMs = 3_000L,
                    )
                target shouldBe 0L
            }

            test("clamps at the first chapter — previous always restarts it, never goes negative") {
                val target = previousChapterTarget(threeChapters, bookPositionMs = 500L, totalDurationMs)
                target shouldBe 0L
            }

            test("chapterless book restarts the book") {
                val target = previousChapterTarget(emptyList(), bookPositionMs = 50_000L, totalDurationMs)
                target shouldBe 0L
            }
        }

        context("nextChapterTarget") {
            test("moves to the start of the next chapter") {
                val target = nextChapterTarget(threeChapters, bookPositionMs = 50_000L, totalDurationMs)
                target shouldBe 100_000L
            }

            test("clamps at the last chapter — stays at its own start") {
                val target = nextChapterTarget(threeChapters, bookPositionMs = 260_000L, totalDurationMs)
                target shouldBe 250_000L
            }

            test("chapterless book has no next target beyond the book start") {
                val target = nextChapterTarget(emptyList(), bookPositionMs = 50_000L, totalDurationMs)
                target shouldBe 0L
            }
        }

        context("hasPreviousChapter / hasNextChapter") {
            test("first chapter still reports a previous chapter — restart-current is always available") {
                val window = currentChapterWindow(threeChapters, bookPositionMs = 0L, totalDurationMs)
                hasPreviousChapter() shouldBe true
                hasNextChapter(threeChapters, window) shouldBe true
            }

            test("middle chapter has both a previous and a next chapter") {
                val window = currentChapterWindow(threeChapters, bookPositionMs = 150_000L, totalDurationMs)
                hasPreviousChapter() shouldBe true
                hasNextChapter(threeChapters, window) shouldBe true
            }

            test("last chapter still reports a previous chapter; has no next chapter") {
                val window = currentChapterWindow(threeChapters, bookPositionMs = 260_000L, totalDurationMs)
                hasPreviousChapter() shouldBe true
                hasNextChapter(threeChapters, window) shouldBe false
            }

            test("chapterless book still reports a previous window (restart-the-book) but no next") {
                val window = currentChapterWindow(emptyList(), bookPositionMs = 50_000L, totalDurationMs)
                hasPreviousChapter() shouldBe true
                hasNextChapter(emptyList(), window) shouldBe false
            }
        }
    })
