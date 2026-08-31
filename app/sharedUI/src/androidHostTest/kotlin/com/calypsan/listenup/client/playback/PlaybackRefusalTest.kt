package com.calypsan.listenup.client.playback

import androidx.media3.common.Player
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tells a refused start apart from an ordinary focus loss.
 *
 * Android 17's background audio hardening refuses `requestAudioFocus()` outright when the app has
 * neither a visible activity nor a running foreground service — the framework logs
 * `AudioHardening … level: partial … exemption: 0` and returns `AUDIOFOCUS_REQUEST_FAILED`.
 * ExoPlayer honours that by putting `playWhenReady` straight back to false.
 *
 * The catch is that a phone call interrupting playback reports the *same* reason. One is a dead
 * end the listener needs a way out of; the other is routine and self-healing. Getting this
 * backwards means either silence when the listener is stuck, or a spurious notification every time
 * someone rings them — so the distinction is drawn here, in one testable place.
 */
class PlaybackRefusalTest :
    FunSpec({

        test("a start refused before any audio played is a refusal") {
            // The hardening case: play was requested, focus was denied, nothing ever sounded.
            isPlaybackRefused(
                playWhenReady = false,
                playRequested = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                wasPlaying = false,
                interruptedByFocusLoss = false,
            ) shouldBe true
        }

        test("losing focus mid-playback is not a refusal") {
            // A phone call. Routine, and playback resumes on its own afterwards.
            isPlaybackRefused(
                playWhenReady = false,
                playRequested = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                wasPlaying = true,
                interruptedByFocusLoss = false,
            ) shouldBe false
        }

        test("the listener pausing is never a refusal") {
            isPlaybackRefused(
                playWhenReady = false,
                playRequested = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                wasPlaying = true,
                interruptedByFocusLoss = false,
            ) shouldBe false

            isPlaybackRefused(
                playWhenReady = false,
                playRequested = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                wasPlaying = false,
                interruptedByFocusLoss = false,
            ) shouldBe false
        }

        test("unplugging headphones is not a refusal") {
            isPlaybackRefused(
                playWhenReady = false,
                playRequested = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
                wasPlaying = true,
                interruptedByFocusLoss = false,
            ) shouldBe false
        }

        test("reaching the end of the book is not a refusal") {
            isPlaybackRefused(
                playWhenReady = false,
                playRequested = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
                wasPlaying = true,
                interruptedByFocusLoss = false,
            ) shouldBe false
        }

        test("starting playback is never a refusal") {
            // playWhenReady = true means the player is going; nothing was refused.
            isPlaybackRefused(
                playWhenReady = true,
                playRequested = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                wasPlaying = false,
                interruptedByFocusLoss = false,
            ) shouldBe false
        }

        test("a stale focus loss replayed on process unfreeze is NOT a refusal") {
            // 2026-08-07, 08:32:29.011 — 79ms after the Android freezer thawed the process, and SIX
            // SECONDS BEFORE the listener tapped anything, this fired and posted
            // PLAYBACK_BLOCKED_IN_BACKGROUND. The loss it described was real but 45 minutes old: at
            // 07:47:09 another app took focus. Frozen processes do not receive callbacks, so Media3
            // delivered it on thaw and the old predicate could not tell "just refused" from "queued
            // since breakfast".
            //
            // playRequested is what separates them: a genuine refusal is always PRECEDED by
            // playWhenReady going true. A replay has no such edge.
            isPlaybackRefused(
                playWhenReady = false,
                playRequested = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                wasPlaying = false,
                interruptedByFocusLoss = false,
            ) shouldBe false
        }

        test("a second focus loss during an interruption is NOT a refusal") {
            // 2026-08-31, in the car. Simon was listening; Android Auto took over, and the phone
            // told him playback "can't start in the background". From `dumpsys audio`:
            //
            //   10:43:11.465  gearhead requestAudioFocus  req=2 (TRANSIENT)
            //   10:43:11.467  ListenUp  focus loss -2, handleLoss   ← paused, still an interruption
            //   10:43:46.839  gearhead requestAudioFocus  req=1 (GAIN)
            //   10:43:46.840  ListenUp  handleLoss                  ← permanent, 35s later
            //   10:43:46.878  ListenUp  posts the refusal notification
            //
            // By the second loss, wasPlaying had gone false (the first loss paused the audio) and
            // playRequested had been re-armed by Media3's focus-driven auto-resume — so the three
            // older inputs read exactly like a cold start the platform refused. They are a LEVEL
            // sampled at the instant of the loss; what tells the two apart is the history:
            // an interruption was already in progress, so this loss continues it rather than
            // refusing anything.
            isPlaybackRefused(
                playWhenReady = false,
                playRequested = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                wasPlaying = false,
                interruptedByFocusLoss = true,
            ) shouldBe false
        }

        test("a refused start with no interruption behind it is still a refusal") {
            // The over-correction guard. Once audio sounds again the interruption is over, so the
            // next cold start the platform refuses must still reach the listener — the 2026-08-31
            // fix buys silence for a continuing interruption, not silence for good.
            isPlaybackRefused(
                playWhenReady = false,
                playRequested = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                wasPlaying = false,
                interruptedByFocusLoss = false,
            ) shouldBe true
        }

        test("a focus loss that interrupts sounding audio arms the interruption") {
            focusInterruptionAfter(
                interruptedByFocusLoss = false,
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                wasPlaying = true,
            ) shouldBe true
        }

        test("a focus loss with nothing sounding does not arm the interruption") {
            // A refusal is not an interruption. Arming here would silence the *next* refusal too,
            // and a listener who taps play twice deserves an answer both times.
            focusInterruptionAfter(
                interruptedByFocusLoss = false,
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                wasPlaying = false,
            ) shouldBe false
        }

        test("only a pause arms the interruption") {
            // A focus-driven change that puts playWhenReady back to TRUE is a resume, whatever was
            // sounding at the time. Arming on it would mean the interruption never ends.
            focusInterruptionAfter(
                interruptedByFocusLoss = false,
                playWhenReady = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                wasPlaying = true,
            ) shouldBe false
        }

        test("an explicit play ends the interruption") {
            // Tapping play is a fresh, deliberate start. If the platform refuses THAT, say so —
            // even though the audio never resumed after the earlier focus loss.
            focusInterruptionAfter(
                interruptedByFocusLoss = true,
                playWhenReady = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                wasPlaying = false,
            ) shouldBe false
        }

        test("an explicit pause ends the interruption") {
            focusInterruptionAfter(
                interruptedByFocusLoss = true,
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                wasPlaying = true,
            ) shouldBe false
        }

        test("a focus-driven auto-resume leaves the interruption armed") {
            // Media3 puts playWhenReady back to true under the same AUDIO_FOCUS_LOSS reason when
            // focus returns. Nothing has sounded yet, so the interruption is still in progress —
            // this is the step that re-arms playRequested in the 2026-08-31 car incident.
            focusInterruptionAfter(
                interruptedByFocusLoss = true,
                playWhenReady = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                wasPlaying = false,
            ) shouldBe true
        }

        test("an unrelated transport change leaves the interruption as it was") {
            focusInterruptionAfter(
                interruptedByFocusLoss = false,
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
                wasPlaying = true,
            ) shouldBe false

            focusInterruptionAfter(
                interruptedByFocusLoss = true,
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
                wasPlaying = true,
            ) shouldBe true
        }
    })
