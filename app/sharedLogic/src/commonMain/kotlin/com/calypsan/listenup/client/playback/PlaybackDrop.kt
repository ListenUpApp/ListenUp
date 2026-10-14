package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.error.TransportError
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long a network drop that interrupts a listen recovers quietly before the listener is told (Simon, 2026-10-14).
 * Measured from the moment the audio stopped. Until then the player keeps saying Buffering while the engine
 * recovers on its own; after it, the error shows and the engine is halted ("error means stopped").
 */
internal val PLAYBACK_DROP_GRACE: Duration = 10.seconds

/**
 * A failure that a returning network can cure: the stream or the server went away. Engines recover from these by
 * themselves (Media3's re-prepare, hls.js's and the browser's own retries, the iOS engine's fresh prepare), so the
 * session gives them [PLAYBACK_DROP_GRACE] before saying anything.
 */
internal fun AppError.isNetworkDrop(): Boolean =
    this is PlaybackError.ConnectionLost ||
        this is PlaybackError.Stalled ||
        this is PlaybackError.ServerUnreachable ||
        (this is TransportError && isRetryable)

/** Where an interrupted listen stands against [PLAYBACK_DROP_GRACE]. */
internal enum class DropStatus {
    /** No drop: audio is moving, paused by the listener, or nothing is interrupted. */
    None,

    /** A drop inside its grace: the player keeps saying Buffering while the engine recovers. */
    Quiet,

    /** A drop that outlasted its grace: any failure behind it shows now. */
    Surfaced,
}

/**
 * The drop bookkeeping the derivation carries from one input to the next. [isListening]: audio has moved for the
 * engine's book and the listener has not paused it since, so a stall or a network failure now interrupts a listen.
 * [dropId] names the current drop, so a grace timer that ran out for an earlier drop never surfaces a later one.
 */
internal data class DropTrack(
    val isListening: Boolean = false,
    val isDropping: Boolean = false,
    val dropId: Int = 0,
) {
    /** This track after the engine reports [inputs] while the session stands at [stage]. Pure. */
    fun next(
        stage: SessionStage?,
        inputs: PhaseInputs?,
    ): DropTrack {
        if (inputs == null || !inputs.isRecoverableFor(stage)) return copy(isListening = false, isDropping = false)
        // Audio moving again is the proof of recovery, whether or not the engine has cleared its failure yet.
        val error = inputs.engineError?.takeUnless { inputs.isRecoveredFrom(it) }
        val listening =
            when {
                error != null -> isListening

                inputs.enginePhase == EnginePhase.PLAYING -> true

                inputs.enginePhase == EnginePhase.PAUSED || inputs.enginePhase == EnginePhase.ENDED -> false

                // IDLE and BUFFERING are where a drop passes through: they say nothing about the listener.
                else -> isListening
            }
        val dropping =
            listening &&
                if (error != null) error.isNetworkDrop() else inputs.enginePhase == EnginePhase.BUFFERING
        return DropTrack(
            isListening = listening,
            isDropping = dropping,
            dropId = if (dropping && !isDropping) dropId + 1 else dropId,
        )
    }

    /** Where this track stands, given the drop whose grace has already run out. */
    fun status(graceSpentFor: Int?): DropStatus =
        when {
            !isDropping -> DropStatus.None
            graceSpentFor == dropId -> DropStatus.Surfaced
            else -> DropStatus.Quiet
        }
}

/** The engine holds the stage's book and no surfaced failure has halted it: the only time a drop can recover. */
private fun PhaseInputs.isRecoverableFor(stage: SessionStage?): Boolean =
    stage is SessionStage.Engine &&
        !stage.halted &&
        forBookId == stage.bookId &&
        engineBookId == stage.bookId

/** The engine plays again behind a network failure it has not cleared yet: the drop is over. */
internal fun PhaseInputs.isRecoveredFrom(error: AppError): Boolean =
    enginePhase == EnginePhase.PLAYING && error.isNetworkDrop()
