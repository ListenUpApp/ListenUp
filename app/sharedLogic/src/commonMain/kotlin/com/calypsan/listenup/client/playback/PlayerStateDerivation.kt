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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * [PlayerSession]'s one state, derived from its [stage] and everything around the loaded book: the engine's facts,
 * the book's position row, the reasons it can't play before Play is pressed (§4.6 proactive causes) and the sleep
 * fade. The session owns the lifecycle and the commands; this owns only the derivation, and writes nothing.
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

    /** The session's one state. Never re-emits on a position tick. */
    val state: StateFlow<PlayerSessionState> =
        combine(restoring, stage, phaseInputs) { isRestoring, s, inputs ->
            when {
                isRestoring -> {
                    PlayerSessionState.Restoring
                }

                s == null -> {
                    PlayerSessionState.NothingLoaded
                }

                else -> {
                    val current = inputs?.takeIf { it.forBookId == s.bookId } ?: neutralInputs(s.bookId)
                    PlayerSessionState.Loaded(
                        bookId = s.bookId,
                        phase = playerPhaseOf(s, current),
                        isEngineBound = s is SessionStage.Engine && current.engineBookId == s.bookId,
                    )
                }
            }
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, PlayerSessionState.Restoring)

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
