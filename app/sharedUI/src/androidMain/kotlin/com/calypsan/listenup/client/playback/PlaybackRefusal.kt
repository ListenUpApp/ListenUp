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
 * Three inputs, because three situations share
 * [Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS] and only one of them is a dead end:
 *
 * - [wasPlaying] separates a refusal from an **interruption**. Audio that was already sounding and
 *   then stopped is a phone call — routine, self-healing, and must stay silent.
 * - [playRequested] separates a refusal from a **replay**. Android freezes backgrounded processes,
 *   and a frozen process receives no callbacks; on thaw, Media3 delivers whatever queued up. On
 *   2026-08-07 a focus loss from 07:47:09 arrived at 08:32:29.011 — 79ms after unfreeze and six
 *   seconds before the listener touched anything — and the old two-input predicate reported it as a
 *   refusal, posting a notification about a play nobody had attempted.
 *
 * [playRequested] is an EDGE, not a level: a genuine refusal is always preceded by `playWhenReady`
 * going true, because that is the app asking to play. A replayed loss has no such edge. (Same
 * lesson as the listening-span START fix: level-triggered state cannot survive a replay.)
 */
internal fun isPlaybackRefused(
    playWhenReady: Boolean,
    playRequested: Boolean,
    reason: Int,
    wasPlaying: Boolean,
): Boolean =
    !playWhenReady &&
        playRequested &&
        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS &&
        !wasPlaying
