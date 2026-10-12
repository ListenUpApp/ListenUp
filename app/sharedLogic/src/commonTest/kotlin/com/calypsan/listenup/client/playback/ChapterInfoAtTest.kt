package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ChapterInfoAtTest :
    FunSpec({
        val chapters =
            listOf(
                Chapter(id = "c0", title = "Prologue", duration = 60_000, startTime = 0),
                Chapter(id = "c1", title = "Chapter 1", duration = 60_000, startTime = 60_000),
                Chapter(id = "c2", title = "Bridge Four", duration = 80_000, startTime = 120_000),
            )

        test("no chapters means no chapter") {
            chapterInfoAt(positionMs = 5_000, chapters = emptyList(), totalDurationMs = 200_000).shouldBeNull()
        }
        test("the chapter that contains the playhead, with its end and the time left") {
            val info = chapterInfoAt(positionMs = 130_000, chapters = chapters, totalDurationMs = 200_000)!!
            info.index shouldBe 2
            info.startMs shouldBe 120_000
            info.endMs shouldBe 200_000
            info.remainingMs shouldBe 70_000
            info.totalChapters shouldBe 3
            info.isGenericTitle shouldBe false
        }
        test("a boundary belongs to the chapter that starts there") {
            chapterInfoAt(positionMs = 60_000, chapters = chapters, totalDurationMs = 200_000)!!.index shouldBe 1
        }
        test("before the first start reads as the first chapter") {
            val late = listOf(Chapter(id = "a", title = "A", duration = 10, startTime = 500))
            chapterInfoAt(positionMs = 0, chapters = late, totalDurationMs = 1_000)!!.index shouldBe 0
        }
        test("generic titles are recognised") {
            chapterInfoAt(positionMs = 61_000, chapters = chapters, totalDurationMs = 200_000)!!.isGenericTitle shouldBe true
        }
        test("an unknown duration ends the last chapter at its own start") {
            chapterInfoAt(positionMs = 130_000, chapters = chapters, totalDurationMs = null)!!.endMs shouldBe 120_000
        }
    })
