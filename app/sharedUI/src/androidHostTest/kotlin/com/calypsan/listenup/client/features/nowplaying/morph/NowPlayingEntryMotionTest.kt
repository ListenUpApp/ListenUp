package com.calypsan.listenup.client.features.nowplaying.morph

import com.calypsan.listenup.client.features.nowplaying.EntryMotion
import com.calypsan.listenup.client.features.nowplaying.NowPlayingLayout
import com.calypsan.listenup.client.features.nowplaying.nowPlayingEntryMotion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NowPlayingEntryMotionTest :
    FunSpec({
        test("the phone bar into the stacked player morphs") {
            nowPlayingEntryMotion(usesPhoneBar = true, layout = NowPlayingLayout.Stacked, destinationChanged = false) shouldBe
                EntryMotion.Morph
        }

        test("a compact fold half open on a table fades through") {
            nowPlayingEntryMotion(true, NowPlayingLayout.Tabletop, false) shouldBe EntryMotion.FadeThrough
        }

        test("the toolbar over a stacked player (600–839 dp portrait) fades through") {
            nowPlayingEntryMotion(false, NowPlayingLayout.Stacked, false) shouldBe EntryMotion.FadeThrough
        }

        test("the toolbar over the side-by-side player fades through") {
            nowPlayingEntryMotion(false, NowPlayingLayout.SideBySide, false) shouldBe EntryMotion.FadeThrough
        }

        test("a close that changes the top destination fades through, wherever it is") {
            nowPlayingEntryMotion(true, NowPlayingLayout.Stacked, destinationChanged = true) shouldBe EntryMotion.FadeThrough
            nowPlayingEntryMotion(false, NowPlayingLayout.SideBySide, destinationChanged = true) shouldBe
                EntryMotion.FadeThrough
        }
    })
