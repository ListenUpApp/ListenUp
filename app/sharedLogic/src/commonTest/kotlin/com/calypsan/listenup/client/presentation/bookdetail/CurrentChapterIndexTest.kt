package com.calypsan.listenup.client.presentation.bookdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CurrentChapterIndexTest :
    FunSpec({
        test("returns null when there are no chapters") {
            currentChapterIndex(emptyList(), positionMs = 0L) shouldBe null
        }

        test("returns the last chapter whose start time is at or before the position") {
            currentChapterIndex(listOf(0L, 100L, 200L), positionMs = 150L) shouldBe 1
        }

        test("returns the first chapter at the very start") {
            currentChapterIndex(listOf(0L, 100L, 200L), positionMs = 0L) shouldBe 0
        }

        test("returns the final chapter when the position is past all start times") {
            currentChapterIndex(listOf(0L, 100L, 200L), positionMs = 5000L) shouldBe 2
        }

        test("returns null when the position precedes every start time") {
            currentChapterIndex(listOf(0L, 100L, 200L), positionMs = -1L) shouldBe null
        }
    })
