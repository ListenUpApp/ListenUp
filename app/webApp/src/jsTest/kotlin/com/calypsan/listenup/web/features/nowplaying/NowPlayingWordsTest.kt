package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.presentation.player.SourceStatus
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch

/** The page's words, the same sentences `en.json` gives the native clients. */
class NowPlayingWordsTest :
    FunSpec({
        test("the status slot speaks only with news, in the browser's wording for an unreachable server") {
            NowPlayingWords.status(SourceStatus.Offline) shouldBe "Offline"
            NowPlayingWords.status(SourceStatus.StreamingTranscoded) shouldBe "Streaming · transcoded"
            NowPlayingWords.status(SourceStatus.ServerUnreachable) shouldBe "Can't reach your server"
            NowPlayingWords.status(SourceStatus.NotPlayable) shouldBe "Not playable"
        }

        test("the counter and the chapter row's current line") {
            NowPlayingWords.counter(number = 6, total = 75) shouldBe "Chapter 6 of 75"
            NowPlayingWords.playingLeft(remainingMs = 511_000) shouldBe "Playing · 8m left"
        }

        test("every card action has its words") {
            NowPlayingWords.action(PlayerCardAction.Retry) shouldBe "Retry"
            NowPlayingWords.action(PlayerCardAction.Dismiss) shouldBe "Dismiss"
            NowPlayingWords.action(PlayerCardAction.CloseBook) shouldBe "Close book"
            NowPlayingWords.action(PlayerCardAction.SignIn) shouldBe "Sign in"
            NowPlayingWords.action(PlayerCardAction.OpenInbox) shouldBe "Open Inbox"
            NowPlayingWords.action(PlayerCardAction.Resume) shouldBe "Resume"
            NowPlayingWords.action(PlayerCardAction.TapToPlay) shouldBe "Play"
            NowPlayingWords.action(PlayerCardAction.ListenAgain) shouldBe "Listen again"
            NowPlayingWords.action(PlayerCardAction.PlayNextBook) shouldBe "Play next book"
        }

        test("the speed sheet's one wall-time figure") {
            PlayerSheetWords.leftAtSpeed(116_640_000) shouldBe "32h 24m left at this speed"
        }

        test("the sleep sheet's lines") {
            PlayerSheetWords.endOfChapterAfter("Bridge Four", 480_000) shouldBe "Pauses after Bridge Four · in 8m"
            PlayerSheetWords.untilEndOf("Bridge Four", 480_000) shouldBe "Until the end of Bridge Four · 8m"
            PlayerSheetWords.pausesAt(1_761_000_000_000, minutes = 30) shouldMatch
                Regex("Pauses at \\d{1,2}:\\d{2}( ?[AP]M)? · 30 min timer")
            PlayerSheetWords.pausesAt(1_761_000_000_000, minutes = null) shouldMatch Regex("Pauses at \\d{1,2}:\\d{2}( ?[AP]M)?")
            PlayerSheetWords.countdown(1_421_000) shouldBe "23:41"
            PlayerSheetWords.meterLabel(1_421_000, 1_800_000) shouldBe "23:41 of 30:00 remaining"
        }

        test("a contributor's books, with no line for none") {
            PlayerSheetWords.books(14) shouldBe "14 books"
            PlayerSheetWords.books(1) shouldBe "1 book"
            PlayerSheetWords.books(0) shouldBe null
        }

        test("a chapter row's label: play from it, or where you are in it, its time left in words") {
            val row = PlayerChapterRow(6, 7, "Anything Reasonable", 0, 1_998_000, isCurrent = false)
            PlayerSheetWords.chapterRowLabel(row, isCurrent = false) shouldBe "Play from chapter 7: Anything Reasonable, 33:18"
            val current = PlayerChapterRow(5, 6, "Bridge Four", 0, 1_235_000, isCurrent = true)
            PlayerSheetWords.chapterRowLabel(current, isCurrent = true, remainingMs = 480_000) shouldBe
                "Chapter 6, Bridge Four, now playing, 8 minutes left"
        }

        test("the chapter list's header sums the book") {
            PlayerSheetWords.chaptersSummary(75, 163_800_000) shouldBe "75 chapters · 45h 30m"
        }

        test("Keep listening is the fade card's one action") {
            NowPlayingWords.action(PlayerCardAction.KeepListening) shouldBe "Keep listening"
        }

        test("the boost row reads Off or the gain") {
            NowPlayingWords.boostRow(0f) shouldBe "Volume boost · Off"
            NowPlayingWords.boostRow(6f) shouldBe "Volume boost · +6 dB"
        }
    })
