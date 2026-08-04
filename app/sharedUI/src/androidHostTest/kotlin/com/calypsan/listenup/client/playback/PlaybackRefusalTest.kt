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
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                wasPlaying = false,
            ) shouldBe true
        }

        test("losing focus mid-playback is not a refusal") {
            // A phone call. Routine, and playback resumes on its own afterwards.
            isPlaybackRefused(
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                wasPlaying = true,
            ) shouldBe false
        }

        test("the listener pausing is never a refusal") {
            isPlaybackRefused(
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                wasPlaying = true,
            ) shouldBe false

            isPlaybackRefused(
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                wasPlaying = false,
            ) shouldBe false
        }

        test("unplugging headphones is not a refusal") {
            isPlaybackRefused(
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
                wasPlaying = true,
            ) shouldBe false
        }

        test("reaching the end of the book is not a refusal") {
            isPlaybackRefused(
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
                wasPlaying = true,
            ) shouldBe false
        }

        test("starting playback is never a refusal") {
            // playWhenReady = true means the player is going; nothing was refused.
            isPlaybackRefused(
                playWhenReady = true,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                wasPlaying = false,
            ) shouldBe false
        }
    })
