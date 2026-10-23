package com.calypsan.listenup.client.presentation.player

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MiniPlayerLinesTest :
    FunSpec({
        test("playing: the book's own chapter title, never our counter") {
            MiniPlayerLines.of(loaded(PlayerPhase.Playing)) shouldBe
                MiniPlayerLine.Chapter(number = null, title = "Bridge Four", sleepArmed = false)
        }

        test("paused and restored read Paused, with the same chapter parts") {
            val paused = MiniPlayerLine.Paused(number = null, title = "Bridge Four", sleepArmed = false)
            MiniPlayerLines.of(loaded(PlayerPhase.Paused)) shouldBe paused
            MiniPlayerLines.of(loaded(PlayerPhase.Restored)) shouldBe paused
        }

        test("a running sleep timer is marked armed on the chapter line") {
            val timer = SleepTimerState.Active(SleepTimerMode.Duration(30), remainingMs = 1_380_000, totalMs = 1_800_000, startedAt = 0)
            MiniPlayerLines.of(loaded(PlayerPhase.Playing, sleepTimer = timer)) shouldBe
                MiniPlayerLine.Chapter(number = null, title = "Bridge Four", sleepArmed = true)
        }

        test("an armed end-of-chapter timer is armed, and the line carries no number of its own") {
            val timer = SleepTimerState.Active(SleepTimerMode.EndOfChapter, remainingMs = 0, totalMs = 0, startedAt = 0)
            MiniPlayerLines.of(loaded(PlayerPhase.Playing, sleepTimer = timer)) shouldBe
                MiniPlayerLine.Chapter(number = null, title = "Bridge Four", sleepArmed = true)
            MiniPlayerLines.of(loaded(PlayerPhase.Paused, sleepTimer = timer)) shouldBe
                MiniPlayerLine.Paused(number = null, title = "Bridge Four", sleepArmed = true)
        }

        test("a generic chapter title is shown as the book names it") {
            MiniPlayerLines.of(loaded(PlayerPhase.Playing, title = "Track 7", isGenericTitle = true)) shouldBe
                MiniPlayerLine.Chapter(number = null, title = "Track 7", sleepArmed = false)
        }

        test("an untitled chapter in a chaptered book falls back to its number, the book gave no name to disagree with") {
            MiniPlayerLines.of(loaded(PlayerPhase.Playing, title = " ", isGenericTitle = true)) shouldBe
                MiniPlayerLine.Chapter(number = 6, title = null, sleepArmed = false)
        }

        test("a chapterless book has neither, and the surface says Whole book") {
            MiniPlayerLines.of(loaded(PlayerPhase.Playing, isWholeBook = true)) shouldBe
                MiniPlayerLine.Chapter(number = null, title = null, sleepArmed = false)
        }

        test("casting names the device, playing or paused") {
            val casting = MiniPlayerLine.Casting(deviceName = "Living Room", number = null, title = "Bridge Four")
            MiniPlayerLines.of(loaded(PlayerPhase.Playing, source = AudioSource.Casting("Living Room"))) shouldBe casting
            MiniPlayerLines.of(loaded(PlayerPhase.Paused, source = AudioSource.Casting("Living Room"))) shouldBe casting
        }

        test("preparing starts, buffering loads, a dropped connection reconnects, the fade and the end say so") {
            MiniPlayerLines.of(loaded(PlayerPhase.Preparing)) shouldBe MiniPlayerLine.Starting
            MiniPlayerLines.of(loaded(PlayerPhase.Buffering)) shouldBe MiniPlayerLine.Loading
            MiniPlayerLines.of(loaded(PlayerPhase.Reconnecting)) shouldBe MiniPlayerLine.Reconnecting
            MiniPlayerLines.of(loaded(PlayerPhase.SleepFading)) shouldBe MiniPlayerLine.FadingOut
            MiniPlayerLines.of(loaded(PlayerPhase.Finished)) shouldBe MiniPlayerLine.Finished
        }

        test("a failure is tinted; a refusal, a lapsed session and every Unavailable are not") {
            val lost = PlaybackError.ConnectionLost()
            MiniPlayerLines.of(loaded(PlayerPhase.Failed(lost))) shouldBe MiniPlayerLine.Problem(lost, isTinted = true)

            val format = PlaybackError.UnsupportedFormat()
            MiniPlayerLines.of(loaded(PlayerPhase.Failed(format))) shouldBe MiniPlayerLine.Problem(format, isTinted = true)

            listOf(PlaybackError.AutoplayBlocked(), PlaybackError.BlockedInBackground(), AuthError.SessionExpired()).forEach { refusal ->
                MiniPlayerLines.of(loaded(PlayerPhase.Failed(refusal))) shouldBe MiniPlayerLine.Problem(refusal, isTinted = false)
            }
            listOf(
                PlaybackError.OfflineNotDownloaded(),
                PlaybackError.ServerUnreachable(),
                PlaybackError.NoLongerAvailable(),
                BookError.HeldForReview(),
                AuthError.SessionExpired(),
            ).forEach { reason ->
                MiniPlayerLines.of(loaded(PlayerPhase.Unavailable(reason))) shouldBe MiniPlayerLine.Problem(reason, isTinted = false)
            }
        }
    })

private fun loaded(
    phase: PlayerPhase,
    title: String = "Bridge Four",
    isGenericTitle: Boolean = false,
    isWholeBook: Boolean = false,
    source: AudioSource = AudioSource.Streaming,
    sleepTimer: SleepTimerState = SleepTimerState.Inactive,
): PlayerUiState.Loaded =
    PlayerUiState.Loaded(
        book = PlayerBook("b1", "The Way of Kings", emptyList(), emptyList(), null, null, emptyList(), 163_800_000, null),
        phase = phase,
        chapter =
            if (isWholeBook) {
                PlayerChapter(0, 1, 1, "", isGenericTitle = true, isWholeBook = true, startMs = 0, durationMs = 163_800_000)
            } else {
                PlayerChapter(5, 6, 75, title, isGenericTitle = isGenericTitle, isWholeBook = false, startMs = 0, durationMs = 1_235_000)
            },
        chapters = emptyList(),
        source = source,
        settings = PlayerSettings(1f, 1f, 0f, 0f, 10, 30),
        sleepTimer = sleepTimer,
        primary = PrimaryControl.Pause,
        showsSkipBack = true,
        canSkipToNextChapter = true,
        finished = null,
    )
