package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.presentation.player.AudioSource
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The page's words, the same sentences `en.json` gives the native clients. */
class NowPlayingWordsTest :
    FunSpec({
        test("the status slot, with the browser's wording for an unreachable server") {
            NowPlayingWords.status(AudioSource.Streaming) shouldBe "Streaming"
            NowPlayingWords.status(AudioSource.StreamingTranscoded) shouldBe "Streaming · transcoded"
            NowPlayingWords.status(AudioSource.ServerUnreachable) shouldBe "Can't reach your server"
            NowPlayingWords.status(AudioSource.Casting("Living Room")) shouldBe "Playing on Living Room"
            NowPlayingWords.status(AudioSource.NotPlayable) shouldBe "Not playable"
        }

        test("the book sentence floors and states content time left") {
            NowPlayingWords.bookSentence(PlayerProgress(0, 0, 18_000_000, 163_800_000)) shouldBe "Book 10% · 40h 30m left"
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

        test("the boost row reads Off or the gain") {
            NowPlayingWords.boostRow(0f) shouldBe "Volume boost · Off"
            NowPlayingWords.boostRow(6f) shouldBe "Volume boost · +6 dB"
        }
    })
