package com.calypsan.listenup.client.presentation.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.data.connection.ConnectionHealthStore
import com.calypsan.listenup.client.domain.model.ConnectionHealth
import com.calypsan.listenup.client.domain.repository.ServerReachability
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI projection of [ConnectionHealth] for the shell banner.
 */
sealed interface ConnectionHealthUi {
    /** Nothing to surface — the banner renders nothing. */
    data object Hidden : ConnectionHealthUi

    /**
     * The server hasn't been reachable for a sustained window. Local content works; sync is
     * parked until reachability returns.
     * Rendered as "Can't reach server" with a Retry action.
     */
    data class Unreachable(
        val sinceMillis: Long,
    ) : ConnectionHealthUi

    /**
     * Session credentials are dead; local content works, sync is parked.
     * Rendered as "Signed out — sign in to sync" with a Sign-in action.
     */
    data object SessionExpired : ConnectionHealthUi

    /**
     * The server's contract version doesn't match this client's — behavioural evidence of a
     * compatibility gap has been observed.
     * Rendered as an update hint with a Dismiss action.
     */
    data class Outdated(
        val clientVersion: String,
        val serverVersion: String,
    ) : ConnectionHealthUi
}

/**
 * Drives the shell-level connection-health banner, derived from [ConnectionHealthStore.state] —
 * the single source of truth for [ConnectionHealth] across reachability, auth liveness, and
 * contract-compat signals (spec §5.2: one source, one projection).
 */
class ConnectionHealthViewModel internal constructor(
    private val healthStore: ConnectionHealthStore,
    private val serverReachability: ServerReachability,
) : ViewModel() {
    /** The banner state; [ConnectionHealthUi.Hidden] whenever the connection is healthy. */
    val state: StateFlow<ConnectionHealthUi> =
        healthStore.state
            .map { it.toUi() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ConnectionHealthUi.Hidden,
            )

    private val eventChannel = Channel<Event>(Channel.BUFFERED)

    /** One-shot navigation/UI events the banner acts on exactly once. */
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    /** Requests navigation to the sign-in screen for the [ConnectionHealthUi.SessionExpired] case. */
    fun signIn() {
        eventChannel.trySend(Event.NavigateToSignIn)
    }

    /** Forces a fresh reachability check for the [ConnectionHealthUi.Unreachable] case. */
    fun retry() {
        viewModelScope.launch { serverReachability.retry() }
    }

    /** Dismisses the current hint for the [ConnectionHealthUi.Outdated] case. */
    fun dismiss() {
        viewModelScope.launch { healthStore.dismissOutdated() }
    }

    /** One-shot events emitted by the connection-health banner. */
    sealed interface Event {
        /** Navigate to the sign-in screen. */
        data object NavigateToSignIn : Event
    }
}

private fun ConnectionHealth.toUi(): ConnectionHealthUi =
    when (this) {
        ConnectionHealth.Healthy -> ConnectionHealthUi.Hidden
        is ConnectionHealth.Unreachable -> ConnectionHealthUi.Unreachable(sinceMillis)
        ConnectionHealth.SessionExpired -> ConnectionHealthUi.SessionExpired
        is ConnectionHealth.Outdated -> ConnectionHealthUi.Outdated(clientVersion, serverVersion)
    }
