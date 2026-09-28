package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.ui.unit.IntRect
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.foldable.Fold
import com.calypsan.listenup.client.foldable.Posture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NowPlayingLayoutTest :
    FunSpec({
        fun window(
            widthDp: Int,
            heightDp: Int,
        ) = WindowSizeClass(widthDp, heightDp)

        test("a phone held upright stacks the player") {
            useWideNowPlaying(window(widthDp = 412, heightDp = 915)) shouldBe false
        }

        test("a small phone on its side goes side by side, so the transport stays on screen") {
            useWideNowPlaying(window(widthDp = 780, heightDp = 360)) shouldBe true
        }

        test("a large phone on its side goes side by side") {
            useWideNowPlaying(window(widthDp = 915, heightDp = 412)) shouldBe true
        }

        test("an unfolded foldable, medium width with room to stack, keeps the stacked player") {
            useWideNowPlaying(window(widthDp = 673, heightDp = 841)) shouldBe false
        }

        test("a tablet is always side by side") {
            useWideNowPlaying(window(widthDp = 1280, heightDp = 800)) shouldBe true
            useWideNowPlaying(window(widthDp = 900, heightDp = 1200)) shouldBe true
        }

        test("a compact-width window stays stacked even when short") {
            useWideNowPlaying(window(widthDp = 500, heightDp = 400)) shouldBe false
        }

        // ── Posture ──────────────────────────────────────────────────────────

        val unfolded = window(widthDp = 673, heightDp = 841)
        val horizontalHinge = IntRect(left = 0, top = 1040, right = 1840, bottom = 1040)

        test("no fold leaves the choice to the window") {
            nowPlayingLayout(unfolded, Fold.None) shouldBe NowPlayingLayout.Stacked
            nowPlayingLayout(window(widthDp = 1280, heightDp = 800), Fold.None) shouldBe NowPlayingLayout.SideBySide
        }

        test("half open with a horizontal hinge splits the player across the fold") {
            nowPlayingLayout(unfolded, Fold(Posture.TABLETOP, horizontalHinge)) shouldBe NowPlayingLayout.Tabletop
        }

        test("tabletop wins over the window, even one wide enough to go side by side") {
            nowPlayingLayout(
                window(widthDp = 1280, heightDp = 800),
                Fold(Posture.TABLETOP, horizontalHinge),
            ) shouldBe NowPlayingLayout.Tabletop
        }

        test("tabletop with no hinge bounds cannot place the split, so it falls back to the window") {
            nowPlayingLayout(unfolded, Fold(Posture.TABLETOP, hingeBounds = null)) shouldBe NowPlayingLayout.Stacked
        }

        test("half open like a book puts the cover and the controls on either page") {
            val verticalHinge = IntRect(left = 1100, top = 0, right = 1100, bottom = 1840)
            nowPlayingLayout(unfolded, Fold(Posture.BOOK, verticalHinge)) shouldBe NowPlayingLayout.SideBySide
        }

        test("a flat fold is just a window") {
            nowPlayingLayout(unfolded, Fold(Posture.NORMAL, horizontalHinge)) shouldBe NowPlayingLayout.Stacked
        }
    })
