package com.calypsan.listenup.client.presentation.campfire

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.campfire.CampfireAnchor
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireInvitableUser
import com.calypsan.listenup.api.dto.campfire.CampfireMember
import com.calypsan.listenup.api.dto.campfire.CampfirePhase
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.ChatMessage
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.campfire.CampfireSessionController
import com.calypsan.listenup.client.campfire.CampfireSessionEvent
import com.calypsan.listenup.client.campfire.CampfireTransport
import com.calypsan.listenup.client.campfire.CampfireUiState
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * ViewModel for the Campfire (co-listening) session screen (campfire implementation plan, Task 9).
 *
 * A thin adapter over one [CampfireSessionController] instance — the client's per-session "session
 * brain" (anchor drift correction, optimistic command apply, presence handling; see its KDoc) —
 * plus the raw [CampfireTransport] for the two calls that live outside any joined session:
 * [createCampfire] (before a session exists) and [listInvitableUsers] (the create/invite sheet's
 * user picker).
 *
 * [CampfireUiState] and [CampfireSessionEvent] are `internal` — campfires have no persistence
 * boundary for a domain model to decouple callers from (see [CampfireTransport]'s KDoc), so Task 8
 * left them as the controller's own internal vocabulary. This VM re-maps them into its own public
 * [CampfireScreenUiState]/[CampfireScreenEvent] — the same "internal domain state, public screen
 * state" split every other screen VM uses, here forced by the Kotlin visibility rule that a public
 * class cannot expose an internal type through a public member.
 *
 * `internal constructor`: constructed only via `CampfireClientModule`'s Koin wiring (mirrors the
 * house idiom already used by e.g. `SeriesEditViewModel`/`AdminInboxViewModel`) — the class itself
 * stays public so `sharedUI` can reference the type for `koinViewModel()`.
 *
 * @property controller One [CampfireSessionController] per screen instance (Koin `factory`),
 * retained for the screen's lifetime; [join]/[rejoin]/[leave] drive its lifecycle.
 * @property transport The create/invite calls that live outside any joined session.
 * @property errorBus Global snackbar surface for create/invite failures (house VM failure-branch shape).
 * @property userRepository Backs [hostDisplayName] — the full-screen Create flow's (task L3) default
 * campfire-name builder needs the caller's own display name reactively, independent of any joined
 * session (the create screen renders before [transport.createSession] is ever called).
 */
class CampfireViewModel internal constructor(
    private val controller: CampfireSessionController,
    private val transport: CampfireTransport,
    private val errorBus: ErrorBus,
    private val userRepository: UserRepository,
) : ViewModel() {
    /**
     * Session id awaiting spoiler confirmation before [confirmSpoilerJoin] hands it to
     * [controller] (see [join]'s KDoc for why the gate lives here rather than in the controller).
     */
    private val pendingSpoilerJoin = MutableStateFlow<CampfireId?>(null)

    /**
     * Mirrors [CampfireSessionController.state], mapped to the public [CampfireScreenUiState] —
     * overlaid with [pendingSpoilerJoin] so a spoiler-gated join reads as
     * [CampfireScreenUiState.ConfirmingSpoiler] instead of [controller]'s (still [CampfireUiState.Idle])
     * state.
     */
    val state: StateFlow<CampfireScreenUiState> =
        combine(controller.state, pendingSpoilerJoin) { controllerState, pendingSessionId ->
            if (pendingSessionId != null) {
                CampfireScreenUiState.ConfirmingSpoiler(pendingSessionId)
            } else {
                controllerState.toScreenState()
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = CampfireScreenUiState.Idle,
        )

    /** Mirrors [CampfireSessionController.events], mapped to the public [CampfireScreenEvent]. */
    val events: Flow<CampfireScreenEvent> = controller.events.map { it.toScreenEvent() }

    /** Create-sheet state — a one-shot RPC outside any joined session. */
    val createState: StateFlow<CampfireCreateUiState>
        field = MutableStateFlow<CampfireCreateUiState>(CampfireCreateUiState.Idle)

    /** Invite-sheet state — populated on demand by [listInvitableUsers]. */
    val inviteState: StateFlow<CampfireInviteUiState>
        field = MutableStateFlow<CampfireInviteUiState>(CampfireInviteUiState.Idle)

    /**
     * The caller's own display name, reactively — the Create screen's default campfire-name builder
     * ("{Host}'s Campfire", built in Kotlin code, never via a localized template — see
     * [com.calypsan.listenup.api.dto.campfire.CampfireSettings.name]'s KDoc). `null` until the local
     * user cache resolves.
     */
    val hostDisplayName: StateFlow<String?> =
        userRepository
            .observeCurrentUser()
            .map { it?.displayName }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = null,
            )

    /**
     * Creates a new session for [bookId] and immediately joins it. The creator becomes the host,
     * but [CampfireSessionController.join] is still what starts the live frame stream and drift
     * loop — [transport.createSession] alone does not.
     */
    fun createCampfire(
        bookId: String,
        settings: CampfireSettings,
    ) {
        viewModelScope.launch {
            createState.value = CampfireCreateUiState.Creating
            when (val result = transport.createSession(bookId, settings)) {
                is AppResult.Success -> {
                    createState.value = CampfireCreateUiState.Idle
                    controller.join(result.data.id)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    createState.value = CampfireCreateUiState.Error(result.error)
                    logger.warn { "createCampfire failed for book $bookId: ${result.error.code}" }
                }
            }
        }
    }

    /**
     * Joins an existing open session (Discover tap, book-detail badge, or an invite).
     *
     * Peeks the snapshot via [transport] directly (the same call [controller] would make
     * internally) so [com.calypsan.listenup.api.dto.campfire.CampfireSnapshot.spoilerAhead] can be
     * inspected *before* [controller] applies the room's anchor to local playback — applying first
     * and confirming after would mean the confirm dialog shows up after the audio has already
     * jumped (and possibly started playing) to the spoiler-ahead position. When the room is not
     * ahead, [controller] is joined immediately; [pendingSpoilerJoin] never leaves the door open to
     * a request in flight [createCampfire] doesn't go through — the creator's own starting anchor
     * can never be spoiler-ahead of themselves, so [controller.join] there is unconditional.
     */
    fun join(sessionId: CampfireId) {
        viewModelScope.launch {
            when (val result = transport.joinSession(sessionId)) {
                is AppResult.Success -> {
                    if (result.data.spoilerAhead) {
                        pendingSpoilerJoin.value = sessionId
                    } else {
                        controller.join(sessionId)
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.warn { "join failed for session $sessionId: ${result.error.code}" }
                }
            }
        }
    }

    /** Confirms a [CampfireScreenUiState.ConfirmingSpoiler] prompt, applying the join for real. */
    fun confirmSpoilerJoin() {
        val sessionId = pendingSpoilerJoin.value ?: return
        pendingSpoilerJoin.value = null
        viewModelScope.launch { controller.join(sessionId) }
    }

    /**
     * Declines a [CampfireScreenUiState.ConfirmingSpoiler] prompt. The peek in [join] already made
     * the caller a member server-side, so this leaves the session rather than lingering as a
     * phantom member no local UI ever surfaces.
     */
    fun cancelSpoilerJoin() {
        val sessionId = pendingSpoilerJoin.value ?: return
        pendingSpoilerJoin.value = null
        viewModelScope.launch { transport.leaveSession(sessionId) }
    }

    /** Re-joins the session last seen [CampfireScreenUiState.Disconnected]. */
    fun rejoin() {
        viewModelScope.launch { controller.rejoin() }
    }

    /** Confirms the pending large-drift resync surfaced by [rejoin] (see [CampfireScreenUiState.Active.pendingRejoinSync]). */
    fun confirmRejoinSync() = controller.confirmRejoinSync()

    /** Leaves the current session. */
    fun leave() {
        viewModelScope.launch { controller.leave() }
    }

    /** Resumes room playback (optimistic; denied via [CampfireScreenEvent.ControlDenied] without control). */
    fun play() = controller.play()

    /** Pauses room playback (optimistic; denied via [CampfireScreenEvent.ControlDenied] without control). */
    fun pause() = controller.pause()

    /** Seeks the room to [positionMs] (optimistic; denied via [CampfireScreenEvent.ControlDenied] without control). */
    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    /** Sets the room's shared playback [speed] (optimistic; denied via [CampfireScreenEvent.ControlDenied] without control). */
    fun setSpeed(speed: Float) = controller.setSpeed(speed)

    /** Sends a chat message to the current session. */
    fun sendChat(text: String) {
        viewModelScope.launch { controller.sendChat(text) }
    }

    /** Sends a reaction to the current session. */
    fun sendReaction(emoji: String) {
        viewModelScope.launch { controller.sendReaction(emoji) }
    }

    /** Starts the campfire (host-only — see [CampfireSessionController.startCampfire]). */
    fun startCampfire() {
        viewModelScope.launch { controller.startCampfire() }
    }

    /** Updates the campfire's lobby settings (host-only, lobby-only — see [CampfireSessionController.updateSettings]). */
    fun updateSettings(settings: CampfireSettings) {
        viewModelScope.launch { controller.updateSettings(settings) }
    }

    /** Populates [inviteState] with [bookId]'s access-filtered invite candidates for the create/invite sheet. */
    fun listInvitableUsers(bookId: String) {
        viewModelScope.launch {
            inviteState.value = CampfireInviteUiState.Loading
            when (val result = transport.listInvitableUsers(bookId)) {
                is AppResult.Success -> {
                    inviteState.value = CampfireInviteUiState.Ready(result.data)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    inviteState.value = CampfireInviteUiState.Error(result.error)
                    logger.warn { "listInvitableUsers failed for book $bookId: ${result.error.code}" }
                }
            }
        }
    }
}

private fun CampfireUiState.toScreenState(): CampfireScreenUiState =
    when (this) {
        CampfireUiState.Idle -> {
            CampfireScreenUiState.Idle
        }

        is CampfireUiState.Joining -> {
            CampfireScreenUiState.Joining(sessionId)
        }

        is CampfireUiState.Active -> {
            CampfireScreenUiState.Active(
                sessionId = sessionId,
                bookId = bookId,
                phase = phase,
                name = name,
                anchor = anchor,
                members = members,
                hostUserId = hostUserId,
                hostDisplayName = members.firstOrNull { it.userId == hostUserId }?.displayName ?: hostUserId,
                controlMode = controlMode,
                chat = chat,
                yourPositionMs = yourPositionMs,
                spoilerAhead = spoilerAhead,
                hasControl = hasControl,
                isHost = isHost,
                startedAtEpochMs = startedAtEpochMs,
                invitedPending = invitedPending,
                inviteOnly = inviteOnly,
                pendingRejoinSync = pendingRejoinSync,
            )
        }

        is CampfireUiState.Disconnected -> {
            CampfireScreenUiState.Disconnected(sessionId, keepPlayingSolo)
        }

        is CampfireUiState.Ended -> {
            CampfireScreenUiState.Ended(sessionId, reason)
        }
    }

private fun CampfireSessionEvent.toScreenEvent(): CampfireScreenEvent =
    when (this) {
        CampfireSessionEvent.ControlDenied -> CampfireScreenEvent.ControlDenied
        CampfireSessionEvent.NotStarted -> CampfireScreenEvent.NotStarted
        is CampfireSessionEvent.ReactionReceived -> CampfireScreenEvent.ReactionReceived(userId, emoji)
    }

/**
 * Public screen-state mirror of [CampfireUiState] (see [CampfireViewModel]'s KDoc for why the
 * mirror exists). Field-for-field identical; UI (Task 10) renders this, never the internal type.
 */
sealed interface CampfireScreenUiState {
    /** No session joined. */
    data object Idle : CampfireScreenUiState

    /** A join or rejoin is in flight. */
    data class Joining(
        val sessionId: CampfireId,
    ) : CampfireScreenUiState

    /**
     * [join] fetched a snapshot whose room position is far enough ahead of the caller's own
     * progress to be spoiler-inducing — the confirm dialog case. Local playback is untouched until
     * [CampfireViewModel.confirmSpoilerJoin] applies it; [CampfireViewModel.cancelSpoilerJoin]
     * backs out instead.
     */
    data class ConfirmingSpoiler(
        val sessionId: CampfireId,
    ) : CampfireScreenUiState

    /**
     * A live, connected session. See [CampfireUiState.Active] for field semantics.
     *
     * @property hostDisplayName [hostUserId]'s display name, resolved from [members] (falling back
     * to the raw id) — the guest lobby's "Waiting for {host} to start" copy.
     */
    data class Active(
        val sessionId: CampfireId,
        val bookId: String,
        val phase: CampfirePhase,
        val name: String,
        val anchor: CampfireAnchor,
        val members: List<CampfireMember>,
        val hostUserId: String,
        val hostDisplayName: String,
        val controlMode: CampfireControlMode,
        val chat: List<ChatMessage>,
        val yourPositionMs: Long?,
        val spoilerAhead: Boolean,
        val hasControl: Boolean,
        val isHost: Boolean,
        val startedAtEpochMs: Long? = null,
        val invitedPending: List<CampfireInvitableUser> = emptyList(),
        val inviteOnly: Boolean = false,
        val pendingRejoinSync: CampfireAnchor? = null,
    ) : CampfireScreenUiState

    /** The live frame stream ended unexpectedly. See [CampfireUiState.Disconnected]. */
    data class Disconnected(
        val sessionId: CampfireId,
        val keepPlayingSolo: Boolean = true,
    ) : CampfireScreenUiState

    /** The session ended for everyone. */
    data class Ended(
        val sessionId: CampfireId,
        val reason: String,
    ) : CampfireScreenUiState
}

/**
 * Public one-shot event mirror of [CampfireSessionEvent] — the house
 * `Channel(Channel.BUFFERED).receiveAsFlow()` idiom, here re-exposed from the controller's own
 * channel (see [CampfireViewModel.events]).
 */
sealed interface CampfireScreenEvent {
    /** The local caller attempted a playback command while lacking control (HOST_ONLY, not host). */
    data object ControlDenied : CampfireScreenEvent

    /** The local caller attempted a playback command before the host started the campfire. */
    data object NotStarted : CampfireScreenEvent

    /** A fire-and-forget reaction arrived — render as a transient overlay, never folded into state. */
    data class ReactionReceived(
        val userId: String,
        val emoji: String,
    ) : CampfireScreenEvent
}

/** UI state for the create-campfire sheet. */
sealed interface CampfireCreateUiState {
    /** No create in flight. */
    data object Idle : CampfireCreateUiState

    /** [CampfireViewModel.createCampfire] is in flight. */
    data object Creating : CampfireCreateUiState

    /** Create failed — the typed [error], never a pre-rendered string (house VM failure-branch shape). */
    data class Error(
        val error: AppError,
    ) : CampfireCreateUiState
}

/** UI state for the invite sheet's user picker. */
sealed interface CampfireInviteUiState {
    /** [CampfireViewModel.listInvitableUsers] has not been called yet. */
    data object Idle : CampfireInviteUiState

    /** [CampfireViewModel.listInvitableUsers] is in flight. */
    data object Loading : CampfireInviteUiState

    /** Access-filtered invite candidates for the requested book. */
    data class Ready(
        val users: List<CampfireInvitableUser>,
    ) : CampfireInviteUiState

    /** Fetch failed — the typed [error], never a pre-rendered string (house VM failure-branch shape). */
    data class Error(
        val error: AppError,
    ) : CampfireInviteUiState
}
