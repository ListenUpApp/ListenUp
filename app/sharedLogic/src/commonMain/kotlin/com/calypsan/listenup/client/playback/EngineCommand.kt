package com.calypsan.listenup.client.playback

/**
 * One instruction for the native (iOS) audio engine, in the order [NativePlayerEngine] issued it.
 * Swift's `PlayerCoordinator` is the only collector, and it executes them one at a time.
 */
sealed interface EngineCommand {
    /** Load [prepared] for [bookId] and play it from [PreparedPlayback.resumePositionMs] (already resolved). */
    data class Load(
        val bookId: String,
        val prepared: PreparedPlayback,
    ) : EngineCommand

    /** Resume. */
    data object Play : EngineCommand

    /** Pause, and persist the place. */
    data object Pause : EngineCommand

    /** Seek to a book-relative position. */
    data class SeekTo(
        val positionMs: Long,
    ) : EngineCommand

    /** Play at [speed]; [isReset] records "follows the default" rather than "this book's own rate". */
    data class SetRate(
        val speed: Float,
        val isReset: Boolean,
    ) : EngineCommand

    /** Boost by [boostDb]; [isReset] as for [SetRate]. */
    data class SetBoostDb(
        val boostDb: Float,
        val isReset: Boolean,
    ) : EngineCommand

    /** Output volume 0..1 (the sleep fade and its restore). */
    data class SetVolume(
        val volume: Float,
    ) : EngineCommand

    /** Stop, unload and deactivate the audio session. */
    data object Stop : EngineCommand
}
