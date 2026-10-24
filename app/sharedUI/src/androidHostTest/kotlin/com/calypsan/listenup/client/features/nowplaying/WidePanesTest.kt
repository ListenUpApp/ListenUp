package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.ui.unit.dp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Spec §5.3: the chapter panel shows only where it fits beside a cover of at least 240dp; the space decides, not the device. */
class WidePanesTest :
    FunSpec({
        fun panes(
            w: Int,
            h: Int,
            short: Boolean = false,
            book: Boolean = false,
        ) = widePanes(maxWidth = w.dp, maxHeight = h.dp, isShort = short, isBookPosture = book)

        test("a landscape tablet holds the cover, the player and the panel") {
            panes(1280, 800 - WIDE_HEADER_HEIGHT_DP) shouldBe WidePanes.ThreeColumns
        }

        test("an upright Expanded tablet stacks the cover over the player, beside the panel") {
            panes(900, 1200) shouldBe WidePanes.ColumnAndPanel
        }

        test("a window that can't fit a 240dp cover beside the panel either way keeps cover and controls, and the sheet") {
            panes(1000, 700 - WIDE_HEADER_HEIGHT_DP) shouldBe WidePanes.CoverAndControls
        }

        test("the three-column threshold is exactly a 240dp cover, the controls' minimum and the panel") {
            panes(THREE_COLUMN_MIN_WIDTH_DP, 744) shouldBe WidePanes.ThreeColumns
            panes(THREE_COLUMN_MIN_WIDTH_DP - 1, 744) shouldNotBe WidePanes.ThreeColumns
        }

        test("a phone on its side never gets the panel") {
            panes(915, 412, short = true) shouldBe WidePanes.CoverAndControls
        }

        test("book posture keeps the two facing pages, so the hinge never cuts through the controls") {
            panes(1280, 744, book = true) shouldBe WidePanes.CoverAndControls
        }
    })
