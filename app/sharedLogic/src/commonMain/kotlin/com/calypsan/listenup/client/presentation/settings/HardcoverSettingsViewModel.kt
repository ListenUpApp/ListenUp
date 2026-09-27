package com.calypsan.listenup.client.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkFailure
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.HardcoverRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

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

    /** Connected as [username] since [since] (epoch ms). [isDisconnecting] while Disconnect is in flight. */
    data class Connected(
        val username: String,
        val since: Long,
        val isDisconnecting: Boolean,
    ) : HardcoverSettingsUiState

    /** Needs a reconnect for [reason]. [isStarting] while Reconnect is in flight. */
    data class Broken(
        val reason: HardcoverBrokenReason,
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
 * approves — so [uiState] is a projection of [HardcoverRepository.observeConnection] plus the two
 * in-flight flags. Actions never move the screen themselves: a successful Connect or Disconnect
 * changes the server's state, and the stream carries that change back here.
 */
class HardcoverSettingsViewModel(
    private val repository: HardcoverRepository,
) : ViewModel() {
    private val starting = MutableStateFlow(false)
    private val disconnecting = MutableStateFlow(false)
    private val eventChannel = Channel<HardcoverSettingsEvent>(Channel.BUFFERED)

    /** One-shot effects — open the approval page, or show an error. Each is delivered once. */
    val events: Flow<HardcoverSettingsEvent> = eventChannel.receiveAsFlow()

    /** The screen's state: [HardcoverSettingsUiState.Loading] until the server first answers. */
    val uiState: StateFlow<HardcoverSettingsUiState> =
        combine(repository.observeConnection(), starting, disconnecting) { connection, isStarting, isDisconnecting ->
            connection.toUiState(isStarting = isStarting, isDisconnecting = isDisconnecting)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = HardcoverSettingsUiState.Loading,
        )

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
}

private fun HardcoverConnection.toUiState(
    isStarting: Boolean,
    isDisconnecting: Boolean,
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
            )
        }

        is HardcoverConnection.Broken -> {
            HardcoverSettingsUiState.Broken(reason = reason, isStarting = isStarting)
        }
    }
