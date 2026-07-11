package com.calypsan.listenup.client.presentation.campfire

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.campfire.CampfireAnchor
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireInvitableUser
import com.calypsan.listenup.api.dto.campfire.CampfireMember
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.ChatMessage
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.campfire.CampfireSessionController
import com.calypsan.listenup.client.campfire.CampfireSessionEvent
import com.calypsan.listenup.client.campfire.CampfireTransport
import com.calypsan.listenup.client.campfire.CampfireUiState
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
 */
class CampfireViewModel internal constructor(
    private val controller: CampfireSessionController,
    private val transport: CampfireTransport,
    private val errorBus: ErrorBus,
) : ViewModel() {
    /** Mirrors [CampfireSessionController.state], mapped to the public [CampfireScreenUiState]. */
    val state: StateFlow<CampfireScreenUiState> =
        controller.state
            .map { it.toScreenState() }
            .stateIn(
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

    /** Joins an existing open session (Discover tap, book-detail badge, or an invite). */
    fun join(sessionId: CampfireId) {
        viewModelScope.launch { controller.join(sessionId) }
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

    /** Populates [inviteState] with [bookId]'s access-filtered invite candidates for the create/invite sheet. */
    fun listInvitableUsers(bookId: String) {
        viewModelScope.launch {
            inviteState.value = CampfireInviteUiState.Loading
            when (val result = transport.listInvitableUsers(bookId)) {
                is AppResult.Success -> inviteState.value = CampfireInviteUiState.Ready(result.data)

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
        CampfireUiState.Idle -> CampfireScreenUiState.Idle

        is CampfireUiState.Joining -> CampfireScreenUiState.Joining(sessionId)

        is CampfireUiState.Active ->
            CampfireScreenUiState.Active(
                sessionId = sessionId,
                bookId = bookId,
                anchor = anchor,
                members = members,
                hostUserId = hostUserId,
                controlMode = controlMode,
                chat = chat,
                yourPositionMs = yourPositionMs,
                spoilerAhead = spoilerAhead,
                hasControl = hasControl,
                pendingRejoinSync = pendingRejoinSync,
            )

        is CampfireUiState.Disconnected -> CampfireScreenUiState.Disconnected(sessionId, keepPlayingSolo)

        is CampfireUiState.Ended -> CampfireScreenUiState.Ended(sessionId, reason)
    }

private fun CampfireSessionEvent.toScreenEvent(): CampfireScreenEvent =
    when (this) {
        CampfireSessionEvent.ControlDenied -> CampfireScreenEvent.ControlDenied
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

    /** A live, connected session. See [CampfireUiState.Active] for field semantics. */
    data class Active(
        val sessionId: CampfireId,
        val bookId: String,
        val anchor: CampfireAnchor,
        val members: List<CampfireMember>,
        val hostUserId: String,
        val controlMode: CampfireControlMode,
        val chat: List<ChatMessage>,
        val yourPositionMs: Long?,
        val spoilerAhead: Boolean,
        val hasControl: Boolean,
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
