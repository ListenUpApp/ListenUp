@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.campfire

import com.calypsan.listenup.api.dto.campfire.CampfireAnchor
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireFrame
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfirePhase
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.PlaybackCommand
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Cadence of the background drift-correction loop while a room is playing. */
private const val DRIFT_TICK_INTERVAL_MS = 5_000L

/**
 * Maximum allowed gap, in milliseconds, between the room's computed position and local
 * playback before a single corrective `seekTo` fires (co-listening design spec §3.2/§9).
 */
private const val DRIFT_TOLERANCE_MS = 1_000L

/**
 * Drift threshold, in milliseconds, above which [CampfireSessionController.rejoin] withholds
 * the automatic seek and instead surfaces [CampfireUiState.Active.pendingRejoinSync] for a
 * confirm dialog — jumping tens of minutes without asking is jarring and can be spoiler-inducing.
 */
private const val LARGE_DRIFT_THRESHOLD_MS = 30_000L

/**
 * Client-side duplicate of the co-listening design spec §3.2 anchor-extrapolation formula.
 * Intentionally duplicated rather than shared with the server's
 * `com.calypsan.listenup.server.campfire.posAt` (`:server`, unreachable from `:sharedLogic` —
 * there is no shared module boundary for a two-line formula over a `:contract` DTO, and Task 1
 * did not place it in `:contract`). A playing anchor advances at [CampfireAnchor.speed] from
 * [CampfireAnchor.capturedAtEpochMs]; a paused anchor is a fixed point at
 * [CampfireAnchor.positionMs] regardless of elapsed time.
 */
private fun CampfireAnchor.posAt(nowEpochMs: Long): Long {
    if (!isPlaying) return positionMs
    val elapsedMs = nowEpochMs - capturedAtEpochMs
    return positionMs + (elapsedMs * speed).toLong()
}

/**
 * The client's "session brain" for one co-listening room (co-listening design spec, Task 8 of
 * the campfire implementation plan). Bridges [CampfireTransport]'s server-authoritative anchor
 * timeline to the existing playback stack ([PlaybackManager] read side, [PlaybackController]
 * write side) without the sync engine ever knowing a campfire exists — campfires are ephemeral
 * and deliberately bypass Room.
 *
 * Responsibilities:
 *  - [join]/[rejoin] apply a fresh [com.calypsan.listenup.api.dto.campfire.CampfireSnapshot] to
 *    local playback and seed [state].
 *  - Incoming [CampfireFrame.AnchorChanged] frames are applied to [playbackController] unless
 *    their `commandId` echoes a command this controller itself sent (optimistic local apply
 *    already happened — see [pause]/[play]/[seekTo]/[setSpeed]).
 *  - A background drift-correction loop nudges local playback back in line with the room's
 *    computed position when they diverge by more than [DRIFT_TOLERANCE_MS], but only while the
 *    room is playing and local playback isn't buffering (co-listening design spec §9).
 *  - A dropped [CampfireTransport.observeSession] stream moves [state] to
 *    [CampfireUiState.Disconnected] — Never Stranded: local playback is left running solo,
 *    never paused out from under the listener. [rejoin] re-snapshots.
 *
 * State shape: [state] is a plain hot [MutableStateFlow] (the house Kotlin 2.4 explicit
 * backing-field idiom — see [SleepTimerManager][com.calypsan.listenup.client.playback.SleepTimerManager]),
 * not a `.stateIn(WhileSubscribed)` derivation. `WhileSubscribed` exists to stop expensive
 * upstream collection when nobody's observing; here the frame-collection and drift-loop jobs are
 * tied to the *session* lifecycle ([join]/[rejoin] start them, [leave] stops them), not to
 * whether the UI currently has [state] open — a backgrounded Now Playing screen must keep
 * processing anchor changes so playback stays in sync when the user returns.
 *
 * Lifecycle: one instance per joined session (Koin `factory { }` — see `CampfireClientModule`).
 * The UI (or its ViewModel) retains the instance for the session's duration and calls [leave]
 * when done; there is no singleton instance to release back to Koin.
 */
internal class CampfireSessionController(
    private val transport: CampfireTransport,
    private val playbackManager: PlaybackManager,
    private val playbackController: PlaybackController,
    private val userRepository: UserRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    /** Current session UI state. See the class KDoc for why this is a plain hot flow, not `stateIn`. */
    val state: StateFlow<CampfireUiState>
        field = MutableStateFlow<CampfireUiState>(CampfireUiState.Idle)

    private val eventChannel = Channel<CampfireSessionEvent>(Channel.BUFFERED)

    /** One-shot events — see [CampfireSessionEvent]. */
    val events: Flow<CampfireSessionEvent> = eventChannel.receiveAsFlow()

    /** Command ids minted by [pause]/[play]/[seekTo]/[setSpeed], awaiting their server echo. */
    private val pendingCommandIds = MutableStateFlow<Set<String>>(emptySet())

    /** The local caller's own user id, resolved on [join]/[rejoin] — drives [hasControl]. */
    private var selfUserId: String? = null

    private var observeJob: Job? = null
    private var driftJob: Job? = null

    /**
     * Joins [sessionId]: fetches a snapshot and starts the live frame stream + drift-correction
     * loop. The snapshot's anchor is applied to local playback ONLY while the room is already
     * [CampfirePhase.LIVE] — a [CampfirePhase.LOBBY] join leaves local playback exactly as it
     * was (the Never Stranded inversion: the user keeps THEIR playback until the fire is lit).
     * [CampfireFrame.CampfireStarted] is what applies the anchor for a still-lobby room, the one
     * shared moment everyone starts from together.
     */
    suspend fun join(sessionId: CampfireId) {
        state.value = CampfireUiState.Joining(sessionId)
        pendingCommandIds.value = emptySet()
        when (val result = transport.joinSession(sessionId)) {
            is AppResult.Success -> {
                selfUserId = userRepository.getCurrentUser()?.idString
                val snapshot = result.data
                // Publish Active first so the UI lands in the room/lobby immediately; then (LIVE only)
                // load the book and apply the anchor — never gate the state transition on the player.
                state.value =
                    CampfireUiState.Active(
                        sessionId = sessionId,
                        bookId = snapshot.bookId,
                        phase = snapshot.phase,
                        name = snapshot.settings.name,
                        anchor = snapshot.anchor,
                        members = snapshot.members,
                        hostUserId = snapshot.hostUserId,
                        controlMode = snapshot.settings.controlMode,
                        chat = snapshot.recentChat,
                        yourPositionMs = snapshot.yourPositionMs,
                        spoilerAhead = snapshot.spoilerAhead,
                        hasControl = hasControl(snapshot.settings.controlMode, snapshot.hostUserId),
                        isHost = isHost(snapshot.hostUserId),
                        startedAtEpochMs = snapshot.startedAtEpochMs,
                        invitedPending = snapshot.invitedPending,
                        inviteOnly = snapshot.settings.inviteOnly,
                    )
                startObserving(sessionId)
                startDriftLoop()
                if (snapshot.phase == CampfirePhase.LIVE) {
                    loadCampfireBook(snapshot.bookId)
                    applyAnchorToPlayback(snapshot.anchor, nowMs())
                }
            }

            is AppResult.Failure -> {
                logger.warn { "Campfire join failed: ${result.error.code}" }
                state.value = CampfireUiState.Idle
            }
        }
    }

    /**
     * Re-joins the session last seen in [CampfireUiState.Disconnected] — a fresh snapshot,
     * applied to local playback unless the room has drifted more than [LARGE_DRIFT_THRESHOLD_MS]
     * from where local playback currently is, in which case the anchor is withheld in
     * [CampfireUiState.Active.pendingRejoinSync] for a confirm dialog (see [confirmRejoinSync])
     * and local playback is left untouched.
     *
     * Refreshes the transport connection FIRST ([CampfireTransport.refreshConnection]),
     * before re-joining and re-subscribing: under the pinned kotlinx.rpc dev-channel build
     * (0.11.0-grpc-189), a cancelled `observeSession` stream can wedge the same connection's
     * next subscription into silently delivering nothing. A fresh connection sidesteps that
     * observed race and is cheap for an explicit user-facing rejoin.
     */
    suspend fun rejoin() {
        val disconnected = state.value as? CampfireUiState.Disconnected ?: return
        val sessionId = disconnected.sessionId
        state.value = CampfireUiState.Joining(sessionId)
        pendingCommandIds.value = emptySet()
        transport.refreshConnection()
        when (val result = transport.joinSession(sessionId)) {
            is AppResult.Success -> {
                selfUserId = userRepository.getCurrentUser()?.idString
                val snapshot = result.data
                var pendingRejoinSync: CampfireAnchor? = null
                var applyLiveAnchor = false
                if (snapshot.phase == CampfirePhase.LIVE) {
                    val roomPositionMs = snapshot.anchor.posAt(nowMs())
                    val localPositionMs = playbackManager.currentPositionMs.value
                    val isLargeDrift = abs(roomPositionMs - localPositionMs) > LARGE_DRIFT_THRESHOLD_MS
                    if (isLargeDrift) {
                        pendingRejoinSync = snapshot.anchor
                    } else {
                        applyLiveAnchor = true
                    }
                }
                // Publish Active first, then (if syncing) load the book and apply the anchor — never
                // gate the state transition on the player.
                state.value =
                    CampfireUiState.Active(
                        sessionId = sessionId,
                        bookId = snapshot.bookId,
                        phase = snapshot.phase,
                        name = snapshot.settings.name,
                        anchor = snapshot.anchor,
                        members = snapshot.members,
                        hostUserId = snapshot.hostUserId,
                        controlMode = snapshot.settings.controlMode,
                        chat = snapshot.recentChat,
                        yourPositionMs = snapshot.yourPositionMs,
                        spoilerAhead = snapshot.spoilerAhead,
                        hasControl = hasControl(snapshot.settings.controlMode, snapshot.hostUserId),
                        isHost = isHost(snapshot.hostUserId),
                        startedAtEpochMs = snapshot.startedAtEpochMs,
                        invitedPending = snapshot.invitedPending,
                        inviteOnly = snapshot.settings.inviteOnly,
                        pendingRejoinSync = pendingRejoinSync,
                    )
                startObserving(sessionId)
                startDriftLoop()
                if (applyLiveAnchor) {
                    loadCampfireBook(snapshot.bookId)
                    applyAnchorToPlayback(snapshot.anchor, nowMs())
                }
            }

            is AppResult.Failure -> {
                logger.warn { "Campfire rejoin failed: ${result.error.code}" }
                state.value = disconnected
            }
        }
    }

    /**
     * Applies a pending [CampfireUiState.Active.pendingRejoinSync] anchor to local playback
     * (the confirm dialog's "yes, jump to sync" action) and clears the flag.
     */
    fun confirmRejoinSync() {
        val current = state.value as? CampfireUiState.Active ?: return
        val pendingAnchor = current.pendingRejoinSync ?: return
        applyAnchorToPlayback(pendingAnchor, nowMs())
        state.value = current.copy(pendingRejoinSync = null)
    }

    /** Leaves the session: stops the frame stream + drift loop and notifies the server. */
    suspend fun leave() {
        val sessionId =
            when (val current = state.value) {
                is CampfireUiState.Active -> current.sessionId
                is CampfireUiState.Disconnected -> current.sessionId
                else -> null
            }
        observeJob?.cancel()
        driftJob?.cancel()
        pendingCommandIds.value = emptySet()
        state.value = CampfireUiState.Idle
        if (sessionId != null) transport.leaveSession(sessionId)
    }

    /** Resumes room playback (optimistic local apply; denied via [CampfireSessionEvent.ControlDenied] without control). */
    fun play() = sendCommand { commandId -> PlaybackCommand.Play(commandId = commandId) }

    /** Pauses room playback (optimistic local apply; denied via [CampfireSessionEvent.ControlDenied] without control). */
    fun pause() = sendCommand { commandId -> PlaybackCommand.Pause(commandId = commandId) }

    /** Seeks the room to [positionMs] (optimistic local apply; denied via [CampfireSessionEvent.ControlDenied] without control). */
    fun seekTo(positionMs: Long) =
        sendCommand { commandId -> PlaybackCommand.SeekTo(positionMs = positionMs, commandId = commandId) }

    /** Sets the room's shared playback [speed] (optimistic local apply; denied via [CampfireSessionEvent.ControlDenied] without control). */
    fun setSpeed(speed: Float) =
        sendCommand { commandId -> PlaybackCommand.SetSpeed(speed = speed, commandId = commandId) }

    /** Sends a chat message. Fire-and-forget pass-through — see [CampfireTransport.sendChat]. */
    suspend fun sendChat(text: String) {
        val sessionId = (state.value as? CampfireUiState.Active)?.sessionId ?: return
        transport.sendChat(sessionId, text)
    }

    /** Sends a reaction. Fire-and-forget pass-through — see [CampfireTransport.sendReaction]. */
    suspend fun sendReaction(emoji: String) {
        val sessionId = (state.value as? CampfireUiState.Active)?.sessionId ?: return
        transport.sendReaction(sessionId, emoji)
    }

    /**
     * Starts the campfire (host-only; the UI shouldn't offer this affordance to a non-host —
     * see [CampfireUiState.Active.isHost]). A [CampfireError.NotController] failure routes the
     * normal failure path (logged, no special denial event) rather than [sendCommand]'s local
     * pre-check, since the UI is expected to have already gated the affordance. The phase flip
     * and anchor apply happen uniformly for every member — including the host — via the
     * broadcast [CampfireFrame.CampfireStarted] frame (see [handleFrame]), not here.
     */
    suspend fun startCampfire() {
        val sessionId = (state.value as? CampfireUiState.Active)?.sessionId ?: return
        val result = transport.startSession(sessionId)
        if (result is AppResult.Failure) {
            logger.warn { "Campfire startSession rejected: ${result.error.code}" }
        }
    }

    /**
     * Updates the campfire's settings while still in the lobby phase (host-only). On success,
     * refreshes the settings-derived slice of [CampfireUiState.Active] ([CampfireUiState.Active.name],
     * [CampfireUiState.Active.controlMode], [CampfireUiState.Active.invitedPending]) from the
     * returned snapshot.
     */
    suspend fun updateSettings(settings: CampfireSettings) {
        val current = state.value as? CampfireUiState.Active ?: return
        when (val result = transport.updateSettings(current.sessionId, settings)) {
            is AppResult.Success -> {
                val snapshot = result.data
                state.value =
                    current.copy(
                        name = snapshot.settings.name,
                        controlMode = snapshot.settings.controlMode,
                        hasControl = hasControl(snapshot.settings.controlMode, current.hostUserId),
                        invitedPending = snapshot.invitedPending,
                        inviteOnly = snapshot.settings.inviteOnly,
                    )
            }

            is AppResult.Failure -> {
                logger.warn { "Campfire updateSettings rejected: ${result.error.code}" }
            }
        }
    }

    /**
     * Mints a commandId, checks [CampfireUiState.Active.phase] and [CampfireUiState.Active.hasControl],
     * applies the command optimistically to [playbackController], and fires it at the server. On a
     * business rejection the pending id is dropped — no echo will ever arrive for it.
     */
    private fun sendCommand(build: (commandId: String) -> PlaybackCommand) {
        val current = state.value as? CampfireUiState.Active ?: return
        if (current.phase == CampfirePhase.LOBBY) {
            eventChannel.trySend(CampfireSessionEvent.NotStarted)
            return
        }
        if (!current.hasControl) {
            eventChannel.trySend(CampfireSessionEvent.ControlDenied)
            return
        }
        val commandId = Uuid.random().toString()
        val command = build(commandId)
        pendingCommandIds.update { it + commandId }
        applyOptimistic(command)
        scope.launch {
            val result = transport.sendCommand(current.sessionId, command)
            if (result is AppResult.Failure) {
                pendingCommandIds.update { it - commandId }
                logger.warn { "Campfire command rejected: ${result.error.code}" }
            }
        }
    }

    /** Applies [command]'s playback side effect only — [CampfireUiState.Active.anchor] updates from the server echo. */
    private fun applyOptimistic(command: PlaybackCommand) {
        when (command) {
            is PlaybackCommand.Play -> playbackController.play()
            is PlaybackCommand.Pause -> playbackController.pause()
            is PlaybackCommand.SeekTo -> playbackController.seekTo(command.positionMs)
            is PlaybackCommand.SetSpeed -> playbackController.setPlaybackSpeed(command.speed)
        }
    }

    private fun startObserving(sessionId: CampfireId) {
        observeJob?.cancel()
        observeJob =
            scope.launch {
                transport.observeSession(sessionId).collect { event ->
                    when (event) {
                        is RpcEvent.Data -> handleFrame(sessionId, event.value)
                        is RpcEvent.Error -> handleDisconnect(sessionId)
                        RpcEvent.Complete -> handleDisconnect(sessionId)
                    }
                }
                // Flow completed without an explicit RpcEvent.Complete marker — e.g. the
                // socket simply dropped. Not reached if leave() cancels this job first.
                handleDisconnect(sessionId)
            }
    }

    /** Runs only while [CampfireUiState.Active.phase] is [CampfirePhase.LIVE] and the room is playing — there's nothing to drift-correct against in a still-[CampfirePhase.LOBBY] room. */
    private fun startDriftLoop() {
        driftJob?.cancel()
        driftJob =
            scope.launch {
                while (isActive) {
                    delay(DRIFT_TICK_INTERVAL_MS)
                    val current = state.value as? CampfireUiState.Active ?: continue
                    val isCorrectable = current.phase == CampfirePhase.LIVE && current.anchor.isPlaying
                    if (!isCorrectable || playbackManager.isBuffering.value) continue
                    val expectedPositionMs = current.anchor.posAt(nowMs())
                    val actualPositionMs = playbackManager.currentPositionMs.value
                    if (abs(expectedPositionMs - actualPositionMs) > DRIFT_TOLERANCE_MS) {
                        playbackController.seekTo(expectedPositionMs)
                    }
                }
            }
    }

    private fun handleFrame(
        sessionId: CampfireId,
        frame: CampfireFrame,
    ) {
        val current = state.value as? CampfireUiState.Active ?: return
        when (frame) {
            is CampfireFrame.CampfireStarted -> {
                // Flip to LIVE immediately so the UI lands in the room the moment the fire is lit —
                // never gated behind the player. Loading the book (and applying the anchor) is a
                // suspend, potentially slow prepare; run it off the frame-collect coroutine so it can't
                // block this transition or the processing of later frames.
                state.value =
                    current.copy(
                        anchor = frame.anchor,
                        phase = CampfirePhase.LIVE,
                        startedAtEpochMs = frame.anchor.capturedAtEpochMs,
                    )
                scope.launch {
                    loadCampfireBook(current.bookId)
                    applyAnchorToPlayback(frame.anchor, nowMs())
                }
            }

            is CampfireFrame.AnchorChanged -> {
                val isOwnEcho = consumePendingCommand(frame.commandId)
                if (!isOwnEcho) applyAnchorToPlayback(frame.anchor, nowMs())
                state.value = current.copy(anchor = frame.anchor)
            }

            is CampfireFrame.MemberJoined -> {
                state.value =
                    current.copy(
                        members = current.members + frame.member,
                        invitedPending = current.invitedPending.filterNot { it.userId == frame.member.userId },
                    )
            }

            is CampfireFrame.MemberLeft -> {
                state.value = current.copy(members = current.members.filterNot { it.userId == frame.member.userId })
            }

            is CampfireFrame.MemberAway -> {
                state.value =
                    current.copy(
                        members = current.members.map { if (it.userId == frame.member.userId) frame.member else it },
                    )
            }

            is CampfireFrame.HostChanged -> {
                state.value =
                    current.copy(
                        hostUserId = frame.userId,
                        hasControl = hasControl(current.controlMode, frame.userId),
                        isHost = isHost(frame.userId),
                    )
            }

            is CampfireFrame.ControlModeChanged -> {
                state.value =
                    current.copy(
                        controlMode = frame.mode,
                        hasControl = hasControl(frame.mode, current.hostUserId),
                    )
            }

            is CampfireFrame.Chat -> {
                state.value = current.copy(chat = current.chat + frame.message)
            }

            is CampfireFrame.Reaction -> {
                eventChannel.trySend(CampfireSessionEvent.ReactionReceived(frame.userId, frame.emoji))
            }

            is CampfireFrame.CampfireEnded -> {
                driftJob?.cancel()
                observeJob?.cancel()
                state.value = CampfireUiState.Ended(sessionId, frame.reason)
            }
        }
    }

    /** Never pauses local playback — see the Never Stranded note in the class KDoc. Idempotent. */
    private fun handleDisconnect(sessionId: CampfireId) {
        driftJob?.cancel()
        if (state.value is CampfireUiState.Active) {
            state.value = CampfireUiState.Disconnected(sessionId = sessionId, keepPlayingSolo = true)
        }
    }

    /**
     * Loads the campfire's book into the local player so a subsequent [applyAnchorToPlayback] acts
     * on the right book. The controller owns campfire playback end to end — the book-detail flow
     * must NOT pre-load or play the book when entering the flow, which would start playback in the
     * lobby before the fire is lit. Called only at the moments the room actually begins playing for
     * this member (a [CampfireFrame.CampfireStarted] frame, or joining a room already [LIVE]) — never
     * in the lobby, and never on routine [CampfireFrame.AnchorChanged] frames (the book is already
     * loaded by then). Idempotent: a no-op when [bookId] is already active (the Never Stranded case
     * where the host was already listening to it). A null [PlaybackManager.prepareForPlayback]
     * (offline, not downloaded) is left to the existing playback-error surface — [applyAnchorToPlayback]
     * then acts on whatever is loaded and the rejoin/drift paths recover once reachable.
     */
    private suspend fun loadCampfireBook(bookId: String) {
        val target = BookId(bookId)
        if (playbackManager.currentBookId.value == target) return
        val prepared = playbackManager.prepareForPlayback(target) ?: return
        playbackManager.activateBook(target)
        playbackController.startPlayback(prepared)
    }

    private fun applyAnchorToPlayback(
        anchor: CampfireAnchor,
        nowMs: Long,
    ) {
        playbackController.seekTo(anchor.posAt(nowMs))
        if (anchor.isPlaying) playbackController.play() else playbackController.pause()
        playbackController.setPlaybackSpeed(anchor.speed)
    }

    private fun hasControl(
        mode: CampfireControlMode,
        hostUserId: String,
    ): Boolean = mode == CampfireControlMode.EVERYONE || selfUserId == hostUserId

    /** Whether the local caller is [hostUserId] — distinct from [hasControl] (see [CampfireUiState.Active.isHost]). */
    private fun isHost(hostUserId: String): Boolean = selfUserId == hostUserId

    /** Atomically removes [commandId] from [pendingCommandIds] if present; returns whether it was. */
    private fun consumePendingCommand(commandId: String?): Boolean {
        if (commandId == null) return false
        var wasPending = false
        pendingCommandIds.update { pending ->
            if (commandId in pending) {
                wasPending = true
                pending - commandId
            } else {
                pending
            }
        }
        return wasPending
    }

    private fun nowMs(): Long = clock.now().toEpochMilliseconds()
}
