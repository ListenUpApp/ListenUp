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
                previousChapterTargetMs(positionMs = 103_100, chapters = chapters) shouldBe 100_000
            }
            test("within 3 s of its start goes to the previous chapter") {
                previousChapterTargetMs(positionMs = 102_900, chapters = chapters) shouldBe 0
            }
            test("exactly 3 s in still goes back a chapter") {
                previousChapterTargetMs(positionMs = 103_000, chapters = chapters) shouldBe 0
            }
            test("chapter 1 always restarts chapter 1") {
                previousChapterTargetMs(positionMs = 1_000, chapters = chapters) shouldBe 0
                previousChapterTargetMs(positionMs = 50_000, chapters = chapters) shouldBe 0
            }
            test("a chapterless book restarts the book") {
                previousChapterTargetMs(positionMs = 50_000, chapters = emptyList()) shouldBe 0
            }
        }

        context("next chapter (§3.3)") {
            test("goes to the start of the next chapter") {
                nextChapterTargetMs(positionMs = 150_000, chapters = chapters) shouldBe 200_000
            }
            test("there is none at the last chapter") {
                nextChapterTargetMs(positionMs = 250_000, chapters = chapters).shouldBeNull()
            }
            test("there is none in a chapterless book") {
                nextChapterTargetMs(positionMs = 0, chapters = emptyList()).shouldBeNull()
            }
        }
    })
