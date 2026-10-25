package com.calypsan.listenup.client.features.documentviewer

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.foldable.Fold
import com.calypsan.listenup.client.foldable.Posture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The reader puts the player beside the page when the screen has room. One spec with iOS's `ReaderLayoutMode`
 * (`ListenUpTests/ReaderLayoutTests.swift`): the cases mirror each other name for name.
 */
class ReaderLayoutTest :
    FunSpec({
        fun window(
            widthDp: Int,
            heightDp: Int,
        ) = WindowSizeClass(widthDp, heightDp)

        val phone = window(widthDp = 412, heightDp = 915)
        val phoneOnItsSide = window(widthDp = 915, heightDp = 412)
        val tablet = window(widthDp = 1280, heightDp = 800)
        val unfolded = window(widthDp = 673, heightDp = 841)
        val verticalHinge = IntRect(left = 1100, top = 0, right = 1100, bottom = 1840)
        val horizontalHinge = IntRect(left = 0, top = 1040, right = 1840, bottom = 1040)

        test("no book in the player keeps the single page") {
            readerLayout(tablet, Fold.None, hasCurrentBook = false) shouldBe ReaderLayout.Single
            readerLayout(unfolded, Fold(Posture.BOOK, verticalHinge), hasCurrentBook = false) shouldBe
                ReaderLayout.Single
        }

        test("half open like a book puts the page and the player on facing pages") {
            readerLayout(unfolded, Fold(Posture.BOOK, verticalHinge), hasCurrentBook = true) shouldBe
                ReaderLayout.Beside(ReaderSplit.Hinge(verticalHinge))
        }

        test("half open on a table keeps the single page and its strip") {
            readerLayout(unfolded, Fold(Posture.TABLETOP, horizontalHinge), hasCurrentBook = true) shouldBe
                ReaderLayout.Single
        }

        test("a wide and tall window puts the player beside the page") {
            readerLayout(tablet, Fold.None, hasCurrentBook = true) shouldBe
                ReaderLayout.Beside(ReaderSplit.TrailingPane)
            readerLayout(window(widthDp = 900, heightDp = 1200), Fold.None, hasCurrentBook = true) shouldBe
                ReaderLayout.Beside(ReaderSplit.TrailingPane)
        }

        test("a narrow window keeps the single page") {
            readerLayout(phone, Fold.None, hasCurrentBook = true) shouldBe ReaderLayout.Single
            readerLayout(unfolded, Fold.None, hasCurrentBook = true) shouldBe ReaderLayout.Single
        }

        test("a wide but short window keeps the single page") {
            readerLayout(phoneOnItsSide, Fold.None, hasCurrentBook = true) shouldBe ReaderLayout.Single
        }

        test("a book fold wins over a narrow window") {
            readerLayout(phone, Fold(Posture.BOOK, verticalHinge), hasCurrentBook = true) shouldBe
                ReaderLayout.Beside(ReaderSplit.Hinge(verticalHinge))
        }

        test("tabletop wins over a wide window") {
            readerLayout(tablet, Fold(Posture.TABLETOP, horizontalHinge), hasCurrentBook = true) shouldBe
                ReaderLayout.Single
        }

        test("a flat fold is just a window") {
            readerLayout(unfolded, Fold(Posture.NORMAL, verticalHinge), hasCurrentBook = true) shouldBe
                ReaderLayout.Single
            readerLayout(tablet, Fold(Posture.NORMAL, verticalHinge), hasCurrentBook = true) shouldBe
                ReaderLayout.Beside(ReaderSplit.TrailingPane)
        }

        // ── Columns (mirrors ReaderColumnsTests) ─────────────────────────────

        fun columns(
            split: ReaderSplit,
            width: Int,
            direction: LayoutDirection = LayoutDirection.Ltr,
            left: Int = 0,
        ) = readerColumns(split, left = left, width = width, paneWidth = 360, clearance = 16, layoutDirection = direction)

        test("a hinge splits the pages with clearance either side") {
            columns(ReaderSplit.Hinge(IntRect(330, 0, 342, 841)), width = 673) shouldBe
                ReaderColumns(page = 330 - 16, gap = 12 + 32, pane = 673 - 342 - 16)
        }

        test("a seamless hinge still keeps clear of the crease") {
            columns(ReaderSplit.Hinge(IntRect(336, 0, 336, 841)), width = 672) shouldBe
                ReaderColumns(page = 320, gap = 32, pane = 320)
        }

        test("right to left puts the page on the right of the hinge") {
            columns(ReaderSplit.Hinge(IntRect(300, 0, 300, 841)), width = 700, direction = LayoutDirection.Rtl) shouldBe
                ReaderColumns(page = 700 - 300 - 16, gap = 32, pane = 300 - 16)
        }

        test("a wide window gives the pane its fixed width") {
            columns(ReaderSplit.TrailingPane, width = 1376) shouldBe ReaderColumns(page = 1376 - 360, gap = 0, pane = 360)
        }

        // Kotlin only: the hinge is in window pixels, iOS's in the reader's own coordinates.
        test("a frame inset from the window's edge measures the hinge from its own edge") {
            columns(ReaderSplit.Hinge(IntRect(336, 0, 336, 841)), width = 652, left = 20) shouldBe
                ReaderColumns(page = 300, gap = 32, pane = 320)
        }

        // Kotlin only: Swift's `PlayerFold.book` always carries its hinge, so it has no such state.
        test("a book fold with no hinge bounds leaves the choice to the window") {
            readerLayout(unfolded, Fold(Posture.BOOK, hingeBounds = null), hasCurrentBook = true) shouldBe
                ReaderLayout.Single
            readerLayout(tablet, Fold(Posture.BOOK, hingeBounds = null), hasCurrentBook = true) shouldBe
                ReaderLayout.Beside(ReaderSplit.TrailingPane)
        }
    })
