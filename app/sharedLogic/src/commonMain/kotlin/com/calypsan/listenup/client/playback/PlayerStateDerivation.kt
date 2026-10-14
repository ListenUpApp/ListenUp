package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * [PlayerSession]'s one state, derived from its [stage] and everything around the loaded book: the engine's facts,
 * the book's position row, the reasons it can't play before Play is pressed (§4.6 proactive causes) and the sleep
 * fade. The session owns the lifecycle and the commands; this owns only the derivation, and writes nothing. It also
 * keeps the time a network drop has been interrupting a listen ([PLAYBACK_DROP_GRACE]): the one grace every platform
 * shares, whichever engine is recovering behind it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LongParameterList") // Each param is one input to the derivation, handed over by the session.
internal class PlayerStateDerivation(
    private val engine: PlayerEngine,
    stage: StateFlow<SessionStage?>,
    restoring: StateFlow<Boolean>,
    stageBookId: Flow<BookId?>,
    positionRepository: PlaybackPositionRepository,
    private val availability: BookAvailability,
    private val inboxRepository: InboxRepository,
    private val networkMonitor: NetworkMonitor,
    private val authSession: AuthSession,
    sleepTimer: SleepTimerManager,
    private val offlineCopiesSupported: Boolean,
    scope: CoroutineScope,
) {
    private val phaseInputs: Flow<PhaseInputs?> =
        stageBookId.flatMapLatest { id ->
            if (id == null) {
                flowOf(null)
            } else {
                val engineFacts =
                    combine(engine.phase, engine.error, engine.loadedBookId) { p, e, b -> Triple(p, e, b) }
                combine(
                    flow = engineFacts,
                    flow2 = positionRepository.observe(id).map { it?.isFinished == true },
                    flow3 = unavailableReason(id),
                    flow4 = sleepTimer.state.map { it is SleepTimerState.FadingOut },
                ) { (phase, error, engineBook), finished, unavailable, fading ->
                    PhaseInputs(
                        forBookId = id,
                        enginePhase = phase,
                        engineError = error,
                        engineBookId = engineBook,
                        isFading = fading,
                        isFinishedRow = finished,
                        unavailable = unavailable,
                    )
                }
            }
        }

    /** The drop whose [PLAYBACK_DROP_GRACE] has run out, by [DropTrack.dropId]; null while none has. */
    private val graceSpentFor = MutableStateFlow<Int?>(null)

    /** The current drop, as the derivation last saw it: what the grace timer runs against. */
    private val currentDrop = MutableStateFlow(DropTrack())

    /**
     * The session's one state. Never re-emits on a position tick. The drop bookkeeping rides the same fold as the
     * phase, so the emission that first carries a network failure already carries its grace: a drop is never shown
     * as Failed for a moment, which would halt the engine before it could recover.
     */
    val state: StateFlow<PlayerSessionState> =
        combine(
            flow = restoring,
            flow2 = stage,
            flow3 = phaseInputs,
            flow4 = graceSpentFor,
            transform = ::DerivationInputs,
        ).scan(Derived(PlayerSessionState.Restoring, DropTrack())) { previous, inputs ->
            derive(previous.drop, inputs)
        }.onEach { currentDrop.value = it.drop }
            .map { it.state }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, PlayerSessionState.Restoring)

    init {
        // One timer per drop: a drop that lasts the whole grace is marked spent, and the next fold surfaces it.
        scope.launch {
            currentDrop
                .map { if (it.isDropping) it.dropId else null }
                .distinctUntilChanged()
                .collectLatest { droppingId ->
                    if (droppingId != null) {
                        delay(PLAYBACK_DROP_GRACE)
                        graceSpentFor.value = droppingId
                    }
                }
        }
    }

    private fun derive(
        drop: DropTrack,
        inputs: DerivationInputs,
    ): Derived {
        val s = inputs.stage
        // The engine's facts are read as they stand now, not as the inputs last carried them: a command that moves
        // the stage and the engine together (a pause out of a drop clears the failure and halts) must never be
        // folded as the new stage over the old failure, which would show it, and halt on it, for a moment.
        val current =
            s?.let { stage ->
                (
                    inputs.phase?.takeIf { it.forBookId == stage.bookId } ?: neutralInputs(
                        stage.bookId,
                    )
                ).withLiveEngineFacts()
            }
        val nextDrop = drop.next(s, current)
        val state =
            when {
                inputs.isRestoring -> {
                    PlayerSessionState.Restoring
                }

                s == null || current == null -> {
                    PlayerSessionState.NothingLoaded
                }

                else -> {
                    PlayerSessionState.Loaded(
                        bookId = s.bookId,
                        phase = playerPhaseOf(s, current.copy(drop = nextDrop.status(inputs.graceSpentFor))),
                        isEngineBound = s is SessionStage.Engine && current.engineBookId == s.bookId,
                    )
                }
            }
        return Derived(state, nextDrop)
    }

    /** Why [bookId] can't play right now, before Play is pressed, or null (§4.6 proactive causes). */
    fun unavailableReason(bookId: BookId): Flow<AppError?> =
        combine(
            flow = inboxRepository.observeHeldBookIds().map { bookId in it },
            flow2 = authSession.authState.map { it is AuthState.SessionLapsed },
            flow3 = availability.observe(bookId),
            flow4 = networkMonitor.onlineFlow,
        ) { held, lapsed, available, online ->
            unavailableReasonOf(
                isHeld = held,
                isSessionLapsed = lapsed,
                hasDownloadedAudio = available.downloadStatus is BookDownloadStatus.Completed,
                canPlay = available.canPlay,
                isDeviceOnline = online,
                offlineCopiesSupported = offlineCopiesSupported,
            )
        }.distinctUntilChanged()

    private fun PhaseInputs.withLiveEngineFacts() =
        copy(
            enginePhase = engine.phase.value,
            engineError = engine.error.value,
            engineBookId = engine.loadedBookId.value,
        )

    private fun neutralInputs(bookId: BookId) =
        PhaseInputs(
            forBookId = bookId,
            enginePhase = engine.phase.value,
            engineError = engine.error.value,
            engineBookId = engine.loadedBookId.value,
            isFading = false,
            isFinishedRow = false,
            unavailable = null,
        )
}

/** Everything the session's state folds over, in one emission. */
private data class DerivationInputs(
    val isRestoring: Boolean,
    val stage: SessionStage?,
    val phase: PhaseInputs?,
    val graceSpentFor: Int?,
)

/** One fold step's output: the state, and the drop bookkeeping the next step continues from. */
private data class Derived(
    val state: PlayerSessionState,
    val drop: DropTrack,
)
