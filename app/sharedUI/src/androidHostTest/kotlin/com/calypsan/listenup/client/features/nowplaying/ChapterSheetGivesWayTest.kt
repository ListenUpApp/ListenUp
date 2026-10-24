package com.calypsan.listenup.client.features.nowplaying

import com.calypsan.listenup.client.presentation.player.PlayerOverlay
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * A chapter sheet left open when the window grows (a phone unfolded, a tablet rotated) gives way to the panel, which
 * lists the same chapters: two chapter lists at once would be one too many. Collapsed, there is no panel, so the
 * tablet toolbar's Chapters still opens the sheet.
 */
class ChapterSheetGivesWayTest :
    FunSpec({
        test("rotating from phone to tablet with the chapter sheet open closes the sheet, because the panel now shows") {
            closesChapterSheet(PlayerOverlay.ChapterPicker, isExpanded = true, showsChapterPanel = true) shouldBe true
        }

        test("without the panel the sheet stays") {
            closesChapterSheet(PlayerOverlay.ChapterPicker, isExpanded = true, showsChapterPanel = false) shouldBe false
        }

        test("collapsed, the toolbar's Chapters opens the sheet, and it stays") {
            closesChapterSheet(PlayerOverlay.ChapterPicker, isExpanded = false, showsChapterPanel = true) shouldBe false
        }

        test("other sheets never give way to the panel") {
            closesChapterSheet(PlayerOverlay.SleepTimer, isExpanded = true, showsChapterPanel = true) shouldBe false
        }
    })
