package com.calypsan.listenup.client.playback

/**
 * The state a refusal verdict needs, carried between the transport events that produce it.
 *
 * [isPlaybackRefused] and [focusInterruptionAfter] are the rules, and they are pure — they answer
 * about one instant. But every bug they exist to fix has been about a *sequence*: a focus loss
 * replayed 45 minutes late (2026-08-07), a car taking focus in two stages (2026-08-31). What tells
 * those apart from a genuine refusal is the state carried between events, and that state used to
 * sit as three loose fields in [PlaybackService] — the one place in this codebase that cannot be
 * instantiated in a test.
 *
 * So it lives here instead: no Android framework beyond the `reason` constants the rules already
 * compare against, no player, no dependencies. `PlaybackRefusalTracker()` in a plain unit test
 * replays a whole incident in a few lines, which is how the sequence gets a regression net at all.
 *
 * The service keeps the *consequences* — the notification, the error bus — and owns none of the
 * reasoning. Main-thread only, exactly as the fields it replaced were: Media3 delivers both of
 * these callbacks on the application thread.
 */
internal class PlaybackRefusalTracker {
    /**
     * Whether audio is actually sounding right now.
     *
     * Read by the rules to separate a refused start from an ordinary interruption, and read by the
     * service as `spanOpen` — "a listening span is open" is the same fact under another name, and
     * keeping one writer is what stops the two readings from ever disagreeing.
     */
    var isAudioSounding: Boolean = false
        private set

    /**
     * Whether a play request is outstanding — `playWhenReady` went true and has not yet resolved
     * into either audio or a refusal.
     *
     * The EDGE that arms the refusal check. Android freezes backgrounded processes and a frozen
     * process receives no callbacks, so on thaw Media3 delivers whatever queued up while it slept.
     * Without this, a focus loss from 45 minutes earlier is indistinguishable from a refusal
     * happening now — which is exactly what fired a spurious "playback blocked" notification 79ms
     * after unfreeze on 2026-08-07.
     */
    private var playRequested = false

    /**
     * Whether an interruption by audio-focus loss is still in progress.
     *
     * [isAudioSounding] is a level sampled at the instant of a loss, so it cannot tell "paused by a
     * focus loss 35 seconds ago" from "never sounded". This is the history it loses. Moved only by
     * [focusInterruptionAfter], and cleared the moment audio sounds again — an interruption is over
     * when the book is talking again, not when a timer says so.
     */
    private var interruptedByFocusLoss = false

    /**
     * Records a transport change and returns whether THIS one is a play the platform refused.
     *
     * Both readings are taken against the state as it stood BEFORE the change, so the rules stay in
     * the two pure functions and this only routes their verdict.
     */
    fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ): Boolean {
        val refused =
            isPlaybackRefused(
                playWhenReady = playWhenReady,
                playRequested = playRequested,
                reason = reason,
                wasPlaying = isAudioSounding,
                interruptedByFocusLoss = interruptedByFocusLoss,
            )
        interruptedByFocusLoss =
            focusInterruptionAfter(
                interruptedByFocusLoss = interruptedByFocusLoss,
                playWhenReady = playWhenReady,
                reason = reason,
                wasPlaying = isAudioSounding,
            )
        // Going true is the app asking to play, which arms the next check; going false resolves the
        // request either way, because the next refusal needs its own request behind it.
        playRequested = playWhenReady
        return refused
    }

    /**
     * Records whether audio is sounding.
     *
     * The service calls this only for events its progress bookkeeping accepts — a report from the
     * player that is not currently the transport is not news about the audio, and must leave this
     * state exactly as it was. See [playbackTransitionFor].
     */
    fun onIsPlayingChanged(isPlaying: Boolean) {
        isAudioSounding = isPlaying
        if (isPlaying) {
            // The book is talking again, so any interruption is over and the next refused start is
            // genuinely cold — it deserves to be reported as one.
            interruptedByFocusLoss = false
        }
    }
}
