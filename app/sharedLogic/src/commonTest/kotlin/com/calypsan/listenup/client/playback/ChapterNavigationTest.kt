package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ChapterNavigationTest :
    FunSpec({
        val chapters =
            listOf(
                Chapter(id = "1", title = "One", duration = 100_000, startTime = 0),
                Chapter(id = "2", title = "Two", duration = 100_000, startTime = 100_000),
                Chapter(id = "3", title = "Three", duration = 100_000, startTime = 200_000),
            )

        context("previous chapter (§3.3)") {
            test("more than 3 s into a chapter restarts it") {
                ChapterNavigation.previousStartMs(positionMs = 103_100, chapters = chapters) shouldBe 100_000
                ChapterNavigation.previousStartMs(positionMs = 103_001, chapters = chapters) shouldBe 100_000
            }
            test("within 3 s of its start goes to the previous chapter") {
                ChapterNavigation.previousStartMs(positionMs = 102_900, chapters = chapters) shouldBe 0
            }
            test("exactly 3 s in still goes back a chapter") {
                ChapterNavigation.previousStartMs(positionMs = 103_000, chapters = chapters) shouldBe 0
            }
            test("chapter 1 always restarts chapter 1") {
                ChapterNavigation.previousStartMs(positionMs = 1_000, chapters = chapters) shouldBe 0
                ChapterNavigation.previousStartMs(positionMs = 50_000, chapters = chapters) shouldBe 0
            }
            test("a chapterless book has no previous: nothing to restart to but the start of the book") {
                ChapterNavigation.previousStartMs(positionMs = 50_000, chapters = emptyList()).shouldBeNull()
                ChapterNavigation.hasPrevious(emptyList()) shouldBe false
            }
            test("a chaptered book always has a previous, even in chapter 1, where it restarts") {
                ChapterNavigation.hasPrevious(chapters) shouldBe true
            }
        }

        context("next chapter (§3.3)") {
            test("goes to the start of the next chapter") {
                ChapterNavigation.nextStartMs(positionMs = 150_000, chapters = chapters) shouldBe 200_000
            }
            test("there is none at the last chapter") {
                ChapterNavigation.nextStartMs(positionMs = 250_000, chapters = chapters).shouldBeNull()
            }
            test("there is none in a chapterless book") {
                ChapterNavigation.nextStartMs(positionMs = 0, chapters = emptyList()).shouldBeNull()
            }
            test("hasNext is false exactly where there is no next start") {
                listOf(0L, 99_999L, 100_000L, 199_999L, 200_000L, 260_000L).forEach { at ->
                    ChapterNavigation.hasNext(at, chapters) shouldBe
                        (ChapterNavigation.nextStartMs(at, chapters) != null)
                }
                ChapterNavigation.hasNext(250_000, chapters) shouldBe false
                ChapterNavigation.hasNext(0, emptyList()) shouldBe false
            }
        }
    })
