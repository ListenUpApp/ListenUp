package com.calypsan.listenup.client.features.nowplaying

import androidx.window.core.layout.WindowSizeClass
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
    })
