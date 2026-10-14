package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.core.BookId

/** What the session knows about the loaded book apart from the engine. */
internal sealed interface SessionStage {
    val bookId: BookId

    /** Loaded, engine not holding it: restored, or after a dismissed prepare failure. */
    data class Idle(
        override val bookId: BookId,
    ) : SessionStage

    /** A load is in flight; only [generation] may finish it. */
    data class Preparing(
        override val bookId: BookId,
        val generation: Int,
    ) : SessionStage

    /**
     * The engine holds it; the engine's facts decide the phase. [halted]: a surfaced failure stopped the engine, so
     * an engine left idle by it is waiting for Play, not preparing. [failure]: the failure the session surfaced and
     * halted on when the engine itself reported none (a drop that outlasted its grace while the server was known to
     * be unreachable), kept so the halt goes on saying why until Retry or Dismiss.
     */
    data class Engine(
        override val bookId: BookId,
        val halted: Boolean = false,
        val failure: AppError? = null,
    ) : SessionStage

    /** The prepare failed with [error]. */
    data class Failed(
        override val bookId: BookId,
        val error: AppError,
    ) : SessionStage

    /** The liveness teardown stopped it; it stays loaded until Close (§3.4). */
    data class Gone(
        override val bookId: BookId,
    ) : SessionStage
}

/** Everything besides the stage that decides the phase, for one book ([forBookId]). */
internal data class PhaseInputs(
    val forBookId: BookId,
    val enginePhase: EnginePhase,
    val engineError: AppError?,
    val engineBookId: BookId?,
    val isFading: Boolean,
    val isFinishedRow: Boolean,
    val unavailable: AppError?,
    val drop: DropStatus = DropStatus.None,
)

/** The phase for [stage]. Pure; the full table is pinned by `PlayerPhaseDerivationTest`. */
internal fun playerPhaseOf(
    stage: SessionStage,
    inputs: PhaseInputs,
): PlayerPhase =
    when (stage) {
        is SessionStage.Gone -> {
            PlayerPhase.Unavailable(PlaybackError.NoLongerAvailable())
        }

        is SessionStage.Failed -> {
            PlayerPhase.Failed(stage.error)
        }

        is SessionStage.Preparing -> {
            PlayerPhase.Preparing
        }

        is SessionStage.Idle -> {
            inputs.unavailable?.let { PlayerPhase.Unavailable(it) }
                ?: if (inputs.isFinishedRow) PlayerPhase.Finished else PlayerPhase.Restored
        }

        is SessionStage.Engine -> {
            engineFailureOf(stage, inputs) ?: enginePhaseOf(stage, inputs)
        }
    }

/**
 * The failure an engine-bound book shows, or null. A network drop inside its grace shows no failure (the engine is
 * recovering, and the player says Reconnecting); one that outlasted it shows the engine's failure, or, while the
 * engine is still retrying in silence, the reason the server can't be reached. A halt keeps showing the failure it
 * stopped on.
 */
private fun engineFailureOf(
    stage: SessionStage.Engine,
    inputs: PhaseInputs,
): PlayerPhase.Failed? {
    val stalledOutOfReach =
        inputs.drop == DropStatus.Surfaced &&
            inputs.enginePhase == EnginePhase.BUFFERING &&
            inputs.unavailable.isServerOutOfReach()
    val failure =
        when {
            inputs.engineBookId != stage.bookId -> null
            inputs.engineError != null && !stage.halted && inputs.isRecoveredFrom(inputs.engineError) -> null
            inputs.engineError != null && inputs.drop.isInGrace -> null
            inputs.engineError != null -> inputs.engineError.explainedBy(inputs.unavailable)
            inputs.enginePhase == EnginePhase.PLAYING -> null
            stage.failure != null -> stage.failure
            stalledOutOfReach -> inputs.unavailable
            else -> null
        }
    return failure?.let(PlayerPhase::Failed)
}

/**
 * The phase an engine-bound book with nothing to show for a failure is in. A network drop inside its grace is
 * Reconnecting whatever the engine reports while it recovers (a failure, a retry's buffering, a fresh prepare); a
 * stall nothing ties to the network is plain Buffering.
 */
private fun enginePhaseOf(
    stage: SessionStage.Engine,
    inputs: PhaseInputs,
): PlayerPhase {
    if (inputs.engineBookId != stage.bookId) return PlayerPhase.Preparing
    if (inputs.drop == DropStatus.Reconnecting && inputs.enginePhase != EnginePhase.PLAYING) {
        return PlayerPhase.Reconnecting
    }
    // A failure the engine is recovering from inside its grace is always a network drop, so Reconnecting above.
    return when (inputs.enginePhase) {
        EnginePhase.IDLE -> if (stage.halted) PlayerPhase.Paused else PlayerPhase.Preparing
        EnginePhase.BUFFERING -> PlayerPhase.Buffering
        EnginePhase.PLAYING -> if (inputs.isFading) PlayerPhase.SleepFading else PlayerPhase.Playing
        EnginePhase.PAUSED -> if (inputs.isFinishedRow) PlayerPhase.Finished else PlayerPhase.Paused
        EnginePhase.ENDED -> PlayerPhase.Finished
    }
}

/** The server can't be reached right now: the proactive reasons a stalled stream is waiting on. */
internal fun AppError?.isServerOutOfReach(): Boolean =
    this is PlaybackError.ServerUnreachable || this is PlaybackError.OfflineNotDownloaded

/**
 * A stream that dropped while the book can't be reached for a known reason (the server is unreachable, the device
 * is offline) is that reason, as a failed prepare is: "Can't reach your server.", not "Couldn't play this book.".
 */
private fun AppError.explainedBy(unavailable: AppError?): AppError =
    if (this is PlaybackError.ConnectionLost &&
        (unavailable is PlaybackError.ServerUnreachable || unavailable is PlaybackError.OfflineNotDownloaded)
    ) {
        unavailable
    } else {
        this
    }

/** Why a not-prepared book can't play right now, or null (§4.6 proactive causes). Pure. */
internal fun unavailableReasonOf(
    isHeld: Boolean,
    isSessionLapsed: Boolean,
    hasDownloadedAudio: Boolean,
    canPlay: Boolean,
    isDeviceOnline: Boolean,
    offlineCopiesSupported: Boolean,
): AppError? =
    when {
        isHeld -> BookError.HeldForReview()
        isSessionLapsed && !hasDownloadedAudio -> AuthError.SessionExpired()
        !canPlay && offlineCopiesSupported && !isDeviceOnline -> PlaybackError.OfflineNotDownloaded()
        !canPlay -> PlaybackError.ServerUnreachable()
        else -> null
    }

/** The loaded book's standing in the local mirror (§3.4). */
internal enum class Liveness { Live, Revoked, RowGone }

/** Pure. A revoked book that is downloaded and partway through keeps playing (the offline-grace rule). */
internal fun livenessOf(
    rowExists: Boolean,
    isLive: Boolean,
    isDownloaded: Boolean,
    positionMs: Long,
    isFinished: Boolean,
): Liveness =
    when {
        !rowExists -> Liveness.RowGone
        !isLive && !(isDownloaded && positionMs > 0 && !isFinished) -> Liveness.Revoked
        else -> Liveness.Live
    }
