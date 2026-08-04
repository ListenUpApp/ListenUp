package com.calypsan.listenup.client.playback

import androidx.media3.common.Player

/**
 * Whether a play request was refused outright, as opposed to interrupted.
 *
 * Android 17's background audio hardening refuses `requestAudioFocus()` when the app has neither a
 * visible activity nor a running foreground service — the framework logs
 * `AudioHardening … level: partial … exemption: 0`, returns `AUDIOFOCUS_REQUEST_FAILED`, and
 * ExoPlayer puts [Player.getPlayWhenReady] straight back to false. Nothing throws, so without this
 * check the listener taps play and the app says nothing at all.
 *
 * We are the first app on a device to meet this: enforcement is gated on target SDK, so while we
 * target 37 the rest of the phone is still in dry-run and logs "would be ignored".
 *
 * [wasPlaying] is what separates the two cases that share
 * [Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS]. Audio that was already sounding and then
 * stopped is an interruption — a phone call — which resolves itself and must stay silent. Audio
 * that never started is a refusal the listener is stuck behind, and needs a way out.
 */
internal fun isPlaybackRefused(
    playWhenReady: Boolean,
    reason: Int,
    wasPlaying: Boolean,
): Boolean =
    !playWhenReady &&
        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS &&
        !wasPlaying
