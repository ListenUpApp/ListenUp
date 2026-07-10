package com.calypsan.listenup.client.presentation.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * UI projection of app-level connection health for the shell banner.
 *
 * Phase 1 carries only the session-lapse case; `Unreachable` and `Outdated` arrive with the
 * `ConnectionHealthStore` in later Connection Resilience phases.
 */
sealed interface ConnectionHealthUi {
    /** Nothing to surface — the banner renders nothing. */
    data object Hidden : ConnectionHealthUi

    /**
     * Session credentials are dead; local content works, sync is parked.
     * Rendered as "Signed out — sign in to sync" with a Sign-in action.
     */
    data object SessionExpired : ConnectionHealthUi
}

/**
 * Drives the shell-level connection-health banner, derived from [AuthSession.authState] —
 * `AuthState.SessionLapsed` is the single source of truth for the lapse (spec §5.2:
 * one source, two projections).
 */
class ConnectionHealthViewModel(
    authSession: AuthSession,
) : ViewModel() {
    /** The banner state; [ConnectionHealthUi.Hidden] whenever the session is healthy. */
    val state: StateFlow<ConnectionHealthUi> =
        authSession.authState
            .map { auth ->
                if (auth is AuthState.SessionLapsed) {
                    ConnectionHealthUi.SessionExpired
                } else {
                    ConnectionHealthUi.Hidden
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ConnectionHealthUi.Hidden,
            )
}
