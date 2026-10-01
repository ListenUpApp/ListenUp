package com.calypsan.listenup.client.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkFailure
import com.calypsan.listenup.api.dto.hardcover.HardcoverSyncProblem
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrNull
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.HardcoverRepository
import com.calypsan.listenup.client.presentation.hardcover.HardcoverBookToMatch
import com.calypsan.listenup.client.presentation.hardcover.HardcoverSyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** How long Sync now's own "Syncing" waits for the server's to take over before letting go. */
private const val SYNC_HANDOFF_MS = 2_000L

/** What the Hardcover settings screen shows. */
sealed interface HardcoverSettingsUiState {
    /** Waiting for the server's first answer. */
    data object Loading : HardcoverSettingsUiState

    /** This server has no Hardcover app; the screen shouldn't be reachable, but renders a short note. */
    data object NotOffered : HardcoverSettingsUiState

    /**
     * Not connected. [lastFailure] explains why the previous attempt ended, if one just did;
     * [isStarting] while Connect is in flight.
     */
    data class NotConnected(
        val lastFailure: HardcoverLinkFailure?,
        val isStarting: Boolean,
    ) : HardcoverSettingsUiState

    /**
     * Waiting for the user to approve [userCode] at [verificationUri] (or the pre-filled
     * [verificationUriComplete]) before [expiresAt] (epoch ms).
     */
    data class Linking(
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val expiresAt: Long,
    ) : HardcoverSettingsUiState

    /**
     * Connected as [username] since [since] (epoch ms). [isDisconnecting] while Disconnect is in flight.
     * [lastSyncedAt] (epoch ms) is the last time anything reached Hardcover or came back, null before the
     * first. [sync] is what the sync line says. [booksToMatch] are the books ListenUp couldn't match,
     * newest first — empty hides the section.
     */
    data class Connected(
        val username: String,
        val since: Long,
        val isDisconnecting: Boolean,
        val lastSyncedAt: Long? = null,
        val sync: HardcoverSyncStatus = HardcoverSyncStatus.Idle,
        val booksToMatch: List<HardcoverBookToMatch> = emptyList(),
    ) : HardcoverSettingsUiState

    /**
     * Was connected as [username] (null when unknown), and needs a reconnect for [reason].
     * [isStarting] while Reconnect is in flight.
     */
    data class Broken(
        val reason: HardcoverBrokenReason,
        val username: String?,
        val isStarting: Boolean,
    ) : HardcoverSettingsUiState
}

/** One-shot effects the Hardcover settings screen performs. */
sealed interface HardcoverSettingsEvent {
    /** Open [url] in the browser: the pre-filled Hardcover approval page, right after Connect. */
    data class OpenVerificationPage(
        val url: String,
    ) : HardcoverSettingsEvent

    /** Show [error] (snackbar or alert). */
    data class ShowError(
        val error: AppError,
    ) : HardcoverSettingsEvent
}

/**
 * Backs Settings → Account → Hardcover: connect with Hardcover's device sign-in, watch it
 * complete, see who you're connected as, disconnect, and reconnect a broken connection.
 *
 * The server owns the connection — it holds the tokens and does the waiting while the user
 * approves — so [uiState] is a projection of [HardcoverRepository.observeConnection] plus the in-flight
 * flags, the sync line ([HardcoverSyncStatus]) and the books that need a match, read from the server
 * and named from Room. Actions never move the screen themselves: a successful Connect or Disconnect
 * changes the server's state, and the stream carries that change back here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HardcoverSettingsViewModel(
    private val repository: HardcoverRepository,
    private val bookRepository: BookRepository,
) : ViewModel() {
    private val starting = MutableStateFlow(false)
    private val disconnecting = MutableStateFlow(false)
    private val requestingSync = MutableStateFlow(false)
    private val eventChannel = Channel<HardcoverSettingsEvent>(Channel.BUFFERED)

    /** One-shot effects — open the approval page, or show an error. Each is delivered once. */
    val events: Flow<HardcoverSettingsEvent> = eventChannel.receiveAsFlow()

    /** The server's stream, subscribed once for both the screen state and the Needs a match list. */
    private val connection: SharedFlow<HardcoverConnection> =
        repository
            .observeConnection()
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), replay = 1)

    /**
     * The books that need a match, named from the library on this device and kept in the server's
     * order. Re-read whenever the connection syncs (its last-sync time moves) and whenever this client
     * links or unlinks a book. Empty until the first answer, so the screen never waits on it; a failed
     * read shows no list rather than a stale one.
     */
    private val booksToMatch: Flow<List<HardcoverBookToMatch>> =
        combine(
            connection
                .map { (it as? HardcoverConnection.Connected)?.let { connected -> connected.lastSyncedAt ?: 0L } }
                .distinctUntilChanged(),
            repository.matchChanges.map { }.onStart { emit(Unit) },
        ) { syncMark, _ -> syncMark }
            .flatMapLatest { syncMark ->
                if (syncMark == null) {
                    flowOf(emptyList())
                } else {
                    flow {
                        emit(
                            repository
                                .booksNeedingMatch()
                                .getOrNull()
                                .orEmpty()
                                .map { it.value },
                        )
                    }.flatMapLatest { ids -> booksNamed(ids) }
                }
            }.onStart { emit(emptyList()) }

    /** The screen's state: [HardcoverSettingsUiState.Loading] until the server first answers. */
    val uiState: StateFlow<HardcoverSettingsUiState> =
        combine(connection, starting, disconnecting, requestingSync, booksToMatch) {
            connection,
            isStarting,
            isDisconnecting,
            isRequestingSync,
            books,
            ->
            connection.toUiState(isStarting, isDisconnecting, isRequestingSync, books)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = HardcoverSettingsUiState.Loading,
        )

    /**
     * Pulls the whole Hardcover shelf now and sends what is waiting. "Syncing" shows from the press:
     * the request returns as soon as the server has queued the work, so this holds on — for up to
     * [SYNC_HANDOFF_MS] — until the server's own stream says it is syncing (or has already ended). A
     * refusal (not connected, needs a reconnect) shows as an error. A press while one is in flight is
     * ignored.
     */
    fun syncNow() {
        if (!requestingSync.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                when (val result = repository.syncNow()) {
                    is AppResult.Success -> {
                        withTimeoutOrNull(SYNC_HANDOFF_MS) {
                            connection.first {
                                it !is HardcoverConnection.Connected || it.isSyncing ||
                                    it.syncProblem == HardcoverSyncProblem.SYNC_NOW_FAILED
                            }
                        }
                    }

                    is AppResult.Failure -> {
                        eventChannel.send(HardcoverSettingsEvent.ShowError(result.error))
                    }
                }
            } finally {
                requestingSync.value = false
            }
        }
    }

    /**
     * Starts a device sign-in (Connect, or Reconnect from Broken) and opens the pre-filled approval
     * page. Ignored while a start is already in flight, so a double tap can't replace the code the
     * user is about to type.
     */
    fun connect() {
        if (!starting.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val event =
                    when (val result = repository.startLink()) {
                        is AppResult.Success -> {
                            HardcoverSettingsEvent.OpenVerificationPage(
                                result.data.verificationUriComplete,
                            )
                        }

                        is AppResult.Failure -> {
                            HardcoverSettingsEvent.ShowError(result.error)
                        }
                    }
                eventChannel.send(event)
            } finally {
                starting.value = false
            }
        }
    }

    /** Opens the current prompt's pre-filled approval page again. Does nothing outside Linking. */
    fun openVerificationPage() {
        val linking = uiState.value as? HardcoverSettingsUiState.Linking ?: return
        viewModelScope.launch {
            eventChannel.send(HardcoverSettingsEvent.OpenVerificationPage(linking.verificationUriComplete))
        }
    }

    /**
     * Disconnects — or, while Linking, cancels the pending sign-in (the server treats both the
     * same). The platforms confirm first. Ignored while a disconnect is already in flight.
     */
    fun disconnect() {
        if (!disconnecting.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val result = repository.disconnect()
                if (result is AppResult.Failure) eventChannel.send(HardcoverSettingsEvent.ShowError(result.error))
            } finally {
                disconnecting.value = false
            }
        }
    }

    private fun booksNamed(ids: List<String>): Flow<List<HardcoverBookToMatch>> =
        if (ids.isEmpty()) {
            flowOf(emptyList())
        } else {
            bookRepository.observeBookListItems(ids).map { items ->
                val byId = items.associateBy { it.id.value }
                ids.mapNotNull { id -> byId[id]?.toBookToMatch() }
            }
        }
}

private fun BookListItem.toBookToMatch() =
    HardcoverBookToMatch(
        bookId = id.value,
        title = title,
        authorNames = authors.joinToString(", ") { it.name },
        coverPath = coverPath,
        coverHash = coverHash,
    )

private fun HardcoverConnection.toUiState(
    isStarting: Boolean,
    isDisconnecting: Boolean,
    isRequestingSync: Boolean,
    booksToMatch: List<HardcoverBookToMatch>,
): HardcoverSettingsUiState =
    when (this) {
        HardcoverConnection.NotOffered -> {
            HardcoverSettingsUiState.NotOffered
        }

        is HardcoverConnection.NotConnected -> {
            HardcoverSettingsUiState.NotConnected(lastFailure = lastLinkFailure, isStarting = isStarting)
        }

        is HardcoverConnection.Linking -> {
            HardcoverSettingsUiState.Linking(
                userCode = prompt.userCode,
                verificationUri = prompt.verificationUri,
                verificationUriComplete = prompt.verificationUriComplete,
                expiresAt = prompt.expiresAt,
            )
        }

        is HardcoverConnection.Connected -> {
            HardcoverSettingsUiState.Connected(
                username = hardcoverUsername,
                since = since,
                isDisconnecting = isDisconnecting,
                lastSyncedAt = lastSyncedAt,
                sync = syncStatusOf(isRequestingSync || isSyncing, syncProblem),
                booksToMatch = booksToMatch,
            )
        }

        is HardcoverConnection.Broken -> {
            HardcoverSettingsUiState.Broken(reason = reason, username = hardcoverUsername, isStarting = isStarting)
        }
    }

/** The sync line: Syncing while a sync runs, else the problem if there is one, else Idle. */
private fun syncStatusOf(
    isSyncing: Boolean,
    problem: HardcoverSyncProblem?,
): HardcoverSyncStatus =
    when {
        isSyncing -> HardcoverSyncStatus.Syncing
        problem != null -> HardcoverSyncStatus.Problem(problem)
        else -> HardcoverSyncStatus.Idle
    }
