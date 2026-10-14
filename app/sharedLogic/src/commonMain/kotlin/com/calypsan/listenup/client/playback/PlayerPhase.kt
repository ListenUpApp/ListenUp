package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.AppError

/** Where playback is for the loaded book. Produced only by [PlayerSession] (Decision 3). */
sealed interface PlayerPhase {
    /** Cold start: shown paused at the saved position; the engine is not prepared (§4.4, S3). */
    data object Restored : PlayerPhase

    /** A book is being prepared; the bar already shows it (§3.6). */
    data object Preparing : PlayerPhase

    /** Loaded and stalled: starting, seeking, or a stream that is slow but still arriving. */
    data object Buffering : PlayerPhase

    /**
     * Stalled because the network dropped under a listen, inside [PLAYBACK_DROP_GRACE]: the engine is recovering by
     * itself, and the listener is told so. It ends in [Playing] when the audio comes back, or [Failed] at the grace.
     */
    data object Reconnecting : PlayerPhase

    /** Audio is advancing. */
    data object Playing : PlayerPhase

    /** Loaded and paused. */
    data object Paused : PlayerPhase

    /** The sleep timer fired and the fade is running. */
    data object SleepFading : PlayerPhase

    /** Played to the end, or the row says finished. Finishing does not unload (§3.7). */
    data object Finished : PlayerPhase

    /** Known before Play is pressed: offline and not downloaded, server unreachable, held, session lapsed, revoked. */
    data class Unavailable(
        val reason: AppError,
    ) : PlayerPhase

    /** Something failed while preparing or playing. */
    data class Failed(
        val error: AppError,
    ) : PlayerPhase
}
