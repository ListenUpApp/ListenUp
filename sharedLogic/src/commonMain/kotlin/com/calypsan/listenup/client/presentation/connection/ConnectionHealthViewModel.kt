package com.calypsan.listenup.client.presentation.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.data.connection.ConnectionHealthStore
import com.calypsan.listenup.client.domain.model.ConnectionHealth
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
    /**
     * Nothing to surface — the banner renders nothing. Also the projection of the domain
     * [ConnectionHealth.Unreachable] state: offline-first means an unreachable server is never
     * ambient banner noise; point-of-need surfaces consume `ServerReachability` directly instead.
     */
    data object Hidden : ConnectionHealthUi

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
 * contract-compat signals (spec §5.2: one source, one projection). The domain
 * [ConnectionHealth.Unreachable] state is deliberately projected to [ConnectionHealthUi.Hidden] —
 * offline-first means there's no ambient "offline" banner; point-of-need surfaces consume
 * `ServerReachability` directly instead.
 */
class ConnectionHealthViewModel internal constructor(
    private val healthStore: ConnectionHealthStore,
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

        // Offline-first: an unreachable server is ambient noise, not a banner. Point-of-need
        // surfaces (book detail, player) consume ServerReachability directly.
        is ConnectionHealth.Unreachable -> ConnectionHealthUi.Hidden

        ConnectionHealth.SessionExpired -> ConnectionHealthUi.SessionExpired

        is ConnectionHealth.Outdated -> ConnectionHealthUi.Outdated(clientVersion, serverVersion)
    }
