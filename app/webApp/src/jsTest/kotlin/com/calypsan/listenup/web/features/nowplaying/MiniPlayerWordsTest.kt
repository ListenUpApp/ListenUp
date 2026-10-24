package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.presentation.player.MiniPlayerLine
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.SleepCountdowns
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** 511 s left in the chapter: "8m". */
private val EIGHT_MINUTES_LEFT =
    PlayerProgress(chapterPositionMs = 724_000, chapterDurationMs = 1_235_000, bookPositionMs = 0, bookDurationMs = 0)

/** 23 minutes on a running sleep timer. */
private const val TWENTY_THREE_MINUTES_MS = 1_380_000L

/**
 * The mini player's second line in the browser's words. The shared [MiniPlayerLine] decides WHAT the line says;
 * these pin how the web bar spells it at each width, and that a chapter is always named by the book's own title,
 * never by our counter (which counts front matter, so "Ch. 16" could sit beside a chapter the book calls 14).
 */
class MiniPlayerWordsTest :
    FunSpec({
        val playing = MiniPlayerLine.Chapter(number = null, title = "Bridge Four", sleepArmed = false)

        test("the desktop bar names the chapter and the author; its times sit by the scrubber") {
            miniLineText(playing, EIGHT_MINUTES_LEFT, MiniLineFit.Desktop, "Brandon Sanderson") shouldBe
                "Bridge Four · Brandon Sanderson"
        }

        test("the phone bar names the chapter and its time left") {
            miniLineText(playing, EIGHT_MINUTES_LEFT, MiniLineFit.Phone, "Brandon Sanderson") shouldBe "Bridge Four · 8m left"
        }

        test("an armed timer replaces the time on a phone") {
            miniLineText(
                playing.copy(sleepArmed = true),
                EIGHT_MINUTES_LEFT,
                MiniLineFit.Phone,
                "",
                sleepRemainingMs = TWENTY_THREE_MINUTES_MS,
            ) shouldBe "Bridge Four · Sleep · 23m"
        }

        test("an armed end-of-chapter timer reads its real countdown, never Sleep · 0m") {
            val armed = playing.copy(sleepArmed = true)
            val endOfChapter = SleepTimerState.Active(SleepTimerMode.EndOfChapter, remainingMs = 0, totalMs = 0, startedAt = 0)
            val countdown = SleepCountdowns.remainingMs(endOfChapter, EIGHT_MINUTES_LEFT, speed = 1f)
            miniLineText(armed, EIGHT_MINUTES_LEFT, MiniLineFit.Phone, "", sleepRemainingMs = countdown) shouldBe
                "Bridge Four · Sleep · 8m"
        }

        test("paused says so before the chapter") {
            val paused = MiniPlayerLine.Paused(null, "Bridge Four", false)
            miniLineText(paused, EIGHT_MINUTES_LEFT, MiniLineFit.Phone, "") shouldBe "Paused · Bridge Four · 8m left"
            miniLineText(paused, EIGHT_MINUTES_LEFT, MiniLineFit.Desktop, "Brandon Sanderson") shouldBe "Paused · Bridge Four"
        }

        test("a generic title stands as the book names it; an untitled chapter is numbered; a chapterless book reads Whole book") {
            miniLineText(MiniPlayerLine.Chapter(null, "Chapter 14", false), EIGHT_MINUTES_LEFT, MiniLineFit.Phone, "") shouldBe
                "Chapter 14 · 8m left"
            miniLineText(MiniPlayerLine.Chapter(6, null, false), EIGHT_MINUTES_LEFT, MiniLineFit.Phone, "") shouldBe
                "Chapter 6 · 8m left"
            miniLineText(MiniPlayerLine.Chapter(null, null, false), EIGHT_MINUTES_LEFT, MiniLineFit.Phone, "") shouldBe
                "Whole book · 8m left"
        }

        test("casting names the device, then the chapter") {
            miniLineText(MiniPlayerLine.Casting("Living Room", null, "Bridge Four"), EIGHT_MINUTES_LEFT, MiniLineFit.Phone, "") shouldBe
                "Playing on Living Room · Bridge Four"
        }

        test("the transitional and terminal states") {
            miniLineText(MiniPlayerLine.Starting, EIGHT_MINUTES_LEFT, MiniLineFit.Phone, "") shouldBe "Starting…"
            miniLineText(MiniPlayerLine.Loading, EIGHT_MINUTES_LEFT, MiniLineFit.Phone, "") shouldBe "Loading…"
            miniLineText(MiniPlayerLine.Reconnecting, EIGHT_MINUTES_LEFT, MiniLineFit.Phone, "") shouldBe "Reconnecting…"
            miniLineText(MiniPlayerLine.FadingOut, EIGHT_MINUTES_LEFT, MiniLineFit.Phone, "") shouldBe "Sleep timer · fading out"
            miniLineText(MiniPlayerLine.Finished, EIGHT_MINUTES_LEFT, MiniLineFit.Phone, "") shouldBe "Finished"
        }

        test("each problem has its short line; others read the card title") {
            fun problem(error: AppError) =
                miniLineText(MiniPlayerLine.Problem(error, isTinted = false), EIGHT_MINUTES_LEFT, MiniLineFit.Phone, "")
            problem(PlaybackError.ServerUnreachable()) shouldBe "Can't reach your server"
            problem(PlaybackError.OfflineNotDownloaded()) shouldBe "Can't play offline"
            problem(AuthError.SessionExpired()) shouldBe "Signed out · place saved"
            problem(PlaybackError.NoLongerAvailable()) shouldBe "No longer in your library"
            problem(BookError.HeldForReview()) shouldBe "Not playable"
            problem(PlaybackError.AutoplayBlocked()) shouldBe "Tap Play to start"
            problem(PlaybackError.BlockedInBackground()) shouldBe "Tap to keep listening"
            problem(PlaybackError.ConnectionLost()) shouldBe "Couldn't play this book."
        }

        test("the spoken line spells the time out, and says whose time it is") {
            miniLineLabel(playing, EIGHT_MINUTES_LEFT) shouldBe "Bridge Four, 8 minutes left in the chapter"
            miniLineLabel(playing.copy(sleepArmed = true), EIGHT_MINUTES_LEFT, sleepRemainingMs = TWENTY_THREE_MINUTES_MS) shouldBe
                "Bridge Four, sleep timer, 23 minutes left"
            miniLineLabel(MiniPlayerLine.Paused(null, "Bridge Four", false), EIGHT_MINUTES_LEFT) shouldBe
                "Paused, Bridge Four, 8 minutes left in the chapter"
            miniLineLabel(MiniPlayerLine.Starting, EIGHT_MINUTES_LEFT) shouldBe "Starting…"
        }

        test("a spoken time is singular at one, and never says zero minutes") {
            spokenDuration(61_000) shouldBe "1 minute"
            spokenDuration(30_000) shouldBe "less than a minute"
            spokenDuration(3_960_000) shouldBe "1 hour 6 minutes"
            spokenDuration(7_200_000) shouldBe "2 hours"
        }
    })
