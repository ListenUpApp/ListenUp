package com.calypsan.listenup.client.playback

import androidx.media3.common.Player
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Drives the refusal state machine through whole incidents, not single verdicts.
 *
 * [PlaybackRefusalTest] proves the rules; this proves the *sequence*. Every bug this machinery
 * exists to fix has been a sequence bug — a focus loss replayed 45 minutes late, a car taking
 * focus in two stages — and a pure function asked one question at a time cannot see either of
 * them. What it takes is the state carried between the events, which is exactly what
 * [PlaybackRefusalTracker] holds.
 *
 * So these tests speak in transport events, in the order Media3 delivers them, and assert only on
 * what the listener is finally told. That makes them the regression net for the wiring too: a
 * tracker that ignored one of its own inputs would pass every rule test in the sibling file and
 * fail here.
 */
class PlaybackRefusalTrackerTest :
    FunSpec({

        test("Android Auto taking over in two stages never tells the listener playback was refused") {
            // 2026-08-31, in the car. Simon was mid-book; Android Auto took over, and the phone
            // told him playback "can't start in the background". From `dumpsys audio`:
            //
            //   10:43:11.465  gearhead requestAudioFocus  req=2 (TRANSIENT)
            //   10:43:11.467  ListenUp  focus loss -2, handleLoss   ← paused, still an interruption
            //   10:43:46.839  gearhead requestAudioFocus  req=1 (GAIN)
            //   10:43:46.840  ListenUp  handleLoss                  ← permanent, 35s later
            //   10:43:46.878  ListenUp  posts the refusal notification
            //
            // Replayed here as the transport events the service actually saw. By the second loss
            // every level-triggered input looked like a cold start: audio was silent, and a play
            // request had been re-armed by Media3's own auto-resume. Only the history separates
            // them.
            val tracker = PlaybackRefusalTracker()

            // The book is talking.
            tracker.onIsPlayingChanged(isPlaying = true)

            // 10:43:11 — Auto takes focus transiently and the audio stops.
            tracker.onPlayWhenReadyChanged(
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            ) shouldBe false
            tracker.onIsPlayingChanged(isPlaying = false)

            // Focus comes back and Media3 auto-resumes — playWhenReady goes true again under the
            // same focus reason, but nothing has sounded yet.
            tracker.onPlayWhenReadyChanged(
                playWhenReady = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            ) shouldBe false

            // 10:43:46 — the permanent loss. This is the one that posted the notification.
            tracker.onPlayWhenReadyChanged(
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            ) shouldBe false
        }

        test("a play the platform refuses before anything has sounded reaches the listener") {
            // Android 17's background hardening: the app asks to play with no visible activity and
            // no foreground service, `requestAudioFocus()` is denied, and ExoPlayer puts
            // playWhenReady straight back to false. Nobody is stuck unless we say so.
            val tracker = PlaybackRefusalTracker()

            tracker.onPlayWhenReadyChanged(
                playWhenReady = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            ) shouldBe false

            tracker.onPlayWhenReadyChanged(
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            ) shouldBe true
        }

        test("once the book is talking again a later refused start reaches the listener") {
            // The over-correction guard, driven as a sequence: the whole car incident, then the
            // audio coming back. Sounding audio is what ends an interruption — if it did not, the
            // silence bought for Android Auto would last until the process died, and the next
            // genuinely refused start would go unreported.
            val tracker = PlaybackRefusalTracker()

            tracker.onIsPlayingChanged(isPlaying = true)
            tracker.onPlayWhenReadyChanged(
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            )
            tracker.onIsPlayingChanged(isPlaying = false)
            tracker.onPlayWhenReadyChanged(
                playWhenReady = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            )
            tracker.onPlayWhenReadyChanged(
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            ) shouldBe false

            // The drive ends, focus comes back for good, and the book talks again.
            tracker.onIsPlayingChanged(isPlaying = true)

            // Bluetooth disconnects on the way out of the car, which pauses playback without any
            // focus loss at all — so nothing here re-arms the interruption.
            tracker.onPlayWhenReadyChanged(
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
            ) shouldBe false
            tracker.onIsPlayingChanged(isPlaying = false)

            // Later, from the headset button, with the app long since backgrounded.
            tracker.onPlayWhenReadyChanged(
                playWhenReady = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
            ) shouldBe false

            tracker.onPlayWhenReadyChanged(
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            ) shouldBe true
        }

        test("a phone call in the middle of a chapter tells the listener nothing") {
            // The commonest focus loss there is, and it reports the same reason as a refusal. Audio
            // that was already sounding and then stopped is an interruption: routine, self-healing,
            // and the listener needs no notification about it.
            val tracker = PlaybackRefusalTracker()

            tracker.onPlayWhenReadyChanged(
                playWhenReady = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            ) shouldBe false
            tracker.onIsPlayingChanged(isPlaying = true)

            tracker.onPlayWhenReadyChanged(
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            ) shouldBe false
        }

        test("a focus loss replayed when the process thaws tells the listener nothing") {
            // 2026-08-07. Simon listened over breakfast and paused; another app took focus at
            // 07:47:09 while the process was frozen, and frozen processes receive no callbacks. On
            // thaw at 08:32:29.011 Media3 delivered the 45-minute-old loss — 79ms after unfreeze
            // and SIX SECONDS BEFORE he touched anything — and it posted a "playback blocked"
            // notification about a play nobody had attempted.
            //
            // What separates a replay from a refusal is the play request: a genuine refusal is
            // always preceded by playWhenReady going true, and the pause below consumed the last
            // one. That is why the request is an edge and not a level.
            val tracker = PlaybackRefusalTracker()

            tracker.onPlayWhenReadyChanged(
                playWhenReady = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            ) shouldBe false
            tracker.onIsPlayingChanged(isPlaying = true)

            tracker.onPlayWhenReadyChanged(
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            ) shouldBe false
            tracker.onIsPlayingChanged(isPlaying = false)

            // 45 minutes of frozen process later, the queued loss finally arrives.
            tracker.onPlayWhenReadyChanged(
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            ) shouldBe false
        }

        test("the service reads sounding audio from the tracker as an open listening span") {
            // The same fact under two names: `spanOpen` for progress bookkeeping, `wasPlaying` for
            // the refusal rules. One writer, so the two can never disagree.
            val tracker = PlaybackRefusalTracker()

            tracker.isAudioSounding shouldBe false

            tracker.onIsPlayingChanged(isPlaying = true)
            tracker.isAudioSounding shouldBe true

            tracker.onIsPlayingChanged(isPlaying = false)
            tracker.isAudioSounding shouldBe false
        }
    })
