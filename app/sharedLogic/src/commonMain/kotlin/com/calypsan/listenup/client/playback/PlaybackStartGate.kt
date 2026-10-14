package com.calypsan.listenup.client.playback

/**
 * The platform's hook around starting a book. A browser only lets a page play sound inside a user gesture,
 * so the web gate spends the tap on the `<audio>` element before the (slow) prepare runs; every other
 * platform needs nothing and binds [NoPlaybackStartGate].
 */
interface PlaybackStartGate {
    /** Inside the gesture, before the prepare. */
    fun prime()

    /** The prepare did not reach the engine: take back what [prime] armed. */
    fun withdraw()

    /** The book is being closed: let go of the platform player entirely. */
    fun release()
}

/** The gate for platforms with no gesture rule. Internal: only sharedLogic's platform modules bind it. */
internal object NoPlaybackStartGate : PlaybackStartGate {
    override fun prime() = Unit

    override fun withdraw() = Unit

    override fun release() = Unit
}
