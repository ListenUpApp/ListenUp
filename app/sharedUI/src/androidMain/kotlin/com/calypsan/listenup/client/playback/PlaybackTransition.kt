package com.calypsan.listenup.client.playback

/** Which player emitted an is-playing event. */
internal enum class TransportSource {
    /** The local [androidx.media3.exoplayer.ExoPlayer]. */
    LOCAL,

    /** The Cast player driving a remote receiver. */
    CAST,
}

/** What the service's progress bookkeeping should do in response to an is-playing event. */
internal enum class PlaybackTransition {
    /** Open a listening span and start the position-persistence loops. */
    START,

    /** Close the span and stop the loops. */
    STOP,

    /** Do nothing — the event came from a player that is not currently the transport. */
    IGNORE,
}

/**
 * Decides how an is-playing event should move the service's progress bookkeeping.
 *
 * One rule does all of it: **only the player that is currently the transport is listened to.**
 * While casting that is the Cast player; otherwise it is the local one. Everything else falls
 * out of that.
 *
 * It matters in both directions. Handing off to Cast releases local audio focus, which pauses
 * the local player — treating that as a real pause would close the span the cast session is
 * about to continue and arm the idle timer that tears the service down. Symmetrically, a
 * trailing event from a torn-down cast session must not stop bookkeeping for the local
 * session that has already taken over.
 *
 * The gap this closes: the cast player had no listener at all, so a cast session begun from a
 * paused state never produced a [START] — no position persistence, no listening span, and a
 * frozen in-app seek bar for its entire duration.
 *
 * [spanOpen] makes [START] an *edge*, not a level. Casting mid-listen hands the same listening
 * session from one player to the other, and both players report it: announcing play twice would
 * open a second span over the open one, discarding everything heard before the hand-off.
 * A [STOP] with no span open stays a [STOP] — it still has an idle timer to arm.
 */
internal fun playbackTransitionFor(
    source: TransportSource,
    isPlaying: Boolean,
    casting: Boolean,
    spanOpen: Boolean,
): PlaybackTransition {
    val activeSource = if (casting) TransportSource.CAST else TransportSource.LOCAL
    return when {
        source != activeSource -> PlaybackTransition.IGNORE
        isPlaying && spanOpen -> PlaybackTransition.IGNORE
        isPlaying -> PlaybackTransition.START
        else -> PlaybackTransition.STOP
    }
}

/**
 * Which player is the transport, and what each is-playing event means for the service's bookkeeping.
 *
 * It keeps the two facts [playbackTransitionFor] decides on — whether the session is casting, and
 * whether audio is sounding (held by [refusalTracker], its one writer) — and moves them together.
 * Apart, they drifted: a hand-off removes the outgoing player's listener, so that player's stop
 * never arrives. When the incoming player did not take over sounding, the flag stayed true, every
 * later play mapped to [PlaybackTransition.IGNORE], and nothing was recorded until the service was
 * recreated. Main-thread only, like the fields it holds.
 */
internal class ActiveTransport(
    private val refusalTracker: PlaybackRefusalTracker,
) {
    /** True while the cast player is the transport. */
    var casting: Boolean = false
        private set

    /** Decides what an is-playing event from [source] does, and records it when it is the transport's news. */
    fun onIsPlayingChanged(
        source: TransportSource,
        isPlaying: Boolean,
    ): PlaybackTransition {
        val transition =
            playbackTransitionFor(
                source = source,
                isPlaying = isPlaying,
                casting = casting,
                spanOpen = refusalTracker.isAudioSounding,
            )
        if (transition != PlaybackTransition.IGNORE) refusalTracker.onIsPlayingChanged(isPlaying)
        return transition
    }

    /**
     * Makes [to] the transport. A listen sounding on the outgoing player ends at the hand-off:
     * [endOutgoingListen] runs while that player is still the transport, so it reads that player's
     * position, and the incoming player's own play opens the next span.
     */
    fun handOff(
        to: TransportSource,
        endOutgoingListen: () -> Unit,
    ) {
        val toCast = to == TransportSource.CAST
        if (toCast == casting) return
        if (refusalTracker.isAudioSounding) {
            refusalTracker.onIsPlayingChanged(false)
            endOutgoingListen()
        }
        casting = toCast
    }
}
