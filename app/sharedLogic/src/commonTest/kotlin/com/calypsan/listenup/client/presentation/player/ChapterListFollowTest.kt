package com.calypsan.listenup.client.presentation.player

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ChapterListFollowTest :
    FunSpec({
        test("the list follows a chapter change only when the old current row was fully in view") {
            ChapterListFollow.onCurrentChanged(previousRowFullyVisible = true) shouldBe ChapterListMove.Recentre
            ChapterListFollow.onCurrentChanged(previousRowFullyVisible = false) shouldBe ChapterListMove.Hold
        }

        test("Back to current chapter shows exactly while the current row is not fully in view") {
            ChapterListFollow.showsBackToCurrent(currentRowFullyVisible = false) shouldBe true
            ChapterListFollow.showsBackToCurrent(currentRowFullyVisible = true) shouldBe false
        }
    })
