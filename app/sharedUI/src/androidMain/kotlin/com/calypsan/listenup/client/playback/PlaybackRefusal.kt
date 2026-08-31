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
 * Four inputs, because four situations share
 * [Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS] and only one of them is a dead end:
 *
 * - [wasPlaying] separates a refusal from an **interruption**. Audio that was already sounding and
 *   then stopped is a phone call — routine, self-healing, and must stay silent.
 * - [playRequested] separates a refusal from a **replay**. Android freezes backgrounded processes,
 *   and a frozen process receives no callbacks; on thaw, Media3 delivers whatever queued up. On
 *   2026-08-07 a focus loss from 07:47:09 arrived at 08:32:29.011 — 79ms after unfreeze and six
 *   seconds before the listener touched anything — and the old two-input predicate reported it as a
 *   refusal, posting a notification about a play nobody had attempted.
 * - [interruptedByFocusLoss] separates a refusal from the **continuation of an interruption**. A
 *   focus loss can arrive in stages: on 2026-08-31 Android Auto took focus transiently at 10:43:11
 *   and permanently at 10:43:46, and by that second loss [wasPlaying] had gone false (the first
 *   loss silenced the audio) while [playRequested] had been re-armed by Media3's focus-driven
 *   auto-resume. Three inputs read that as a cold start the platform refused, and told a listener
 *   mid-book to open the app.
 *
 * [playRequested] is an EDGE, not a level: a genuine refusal is always preceded by `playWhenReady`
 * going true, because that is the app asking to play. A replayed loss has no such edge. (Same
 * lesson as the listening-span START fix: level-triggered state cannot survive a replay.)
 *
 * [interruptedByFocusLoss] is the same lesson one field over: it is the HISTORY [wasPlaying] loses
 * by being sampled at the instant of the loss. Deliberately not a time window — a phone call can
 * run ten minutes, so any window would be wrong at its boundary; the flag records the fact instead.
 * [focusInterruptionAfter] is where it is kept honest.
 */
internal fun isPlaybackRefused(
    playWhenReady: Boolean,
    playRequested: Boolean,
    reason: Int,
    wasPlaying: Boolean,
    interruptedByFocusLoss: Boolean,
): Boolean =
    !playWhenReady &&
        playRequested &&
        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS &&
        !wasPlaying &&
        !interruptedByFocusLoss

/**
 * Whether an interruption by focus loss is in progress once this transport change is accounted for.
 *
 * The `interruptedByFocusLoss` input of [isPlaybackRefused], and the only place it moves — so the
 * whole rule stays here rather than as conditionals sprinkled through the service.
 *
 * - A focus loss that silences **sounding** audio arms it: that is an interruption, and whatever
 *   the platform does next is still part of it. A focus loss with nothing sounding does not arm it,
 *   because a refusal is not an interruption — arming there would silence the *next* refusal too.
 * - [Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST] disarms it, in either direction. Tapping
 *   play is a fresh, deliberate start; if the platform refuses THAT, the listener must be told,
 *   even though audio never resumed after the earlier loss.
 * - Everything else leaves it as it was. Notably Media3's auto-resume on regained focus, which
 *   reports the same `AUDIO_FOCUS_LOSS` reason with `playWhenReady` back to true — the
 *   interruption is not over until audio actually sounds again, which the service observes
 *   directly and clears there.
 */
internal fun focusInterruptionAfter(
    interruptedByFocusLoss: Boolean,
    playWhenReady: Boolean,
    reason: Int,
    wasPlaying: Boolean,
): Boolean =
    when {
        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> false

        !playWhenReady &&
            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS &&
            wasPlaying -> true

        else -> interruptedByFocusLoss
    }
