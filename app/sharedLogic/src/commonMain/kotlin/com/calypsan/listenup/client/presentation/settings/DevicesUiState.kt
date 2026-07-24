package com.calypsan.listenup.client.presentation.settings

import com.calypsan.listenup.api.error.AppError

/**
 * A device row in the Devices screen — display-resolved from a server `SessionSummary`.
 *
 * Name and descriptor are resolved once in the ViewModel so the Composable stays
 * a pure rendering of strings; [secondary] may be blank when no device metadata
 * is available.
 */
data class DeviceRow(
    val sessionId: String,
    val displayName: String,
    val secondary: String, // "iOS 17.2 · ListenUp 1.0.0" — may be blank
    val lastUsedAt: Long,
    val isCurrent: Boolean,
    val deviceType: String? = null,
)

/**
 * UI state for the Devices screen.
 *
 * Sealed hierarchy:
 * - [Loading] before the first `listSessions` response.
 * - [Ready] once sessions have loaded; carries the resolved device rows and the
 *   set of session ids currently being signed out (`signingOut`) for per-row overlays.
 * - [Error] when the load fails — the screen offers a retry.
 */
sealed interface DevicesUiState {
    /** Pre-first-response placeholder. */
    data object Loading : DevicesUiState

    /** Sessions loaded; carries device rows and the in-flight sign-out set. */
    data class Ready(
        val devices: List<DeviceRow>,
        val signingOut: Set<String> = emptySet(),
    ) : DevicesUiState

    /** Terminal state when the session load fails; carries the typed [error] for the UI to localize. */
    data class Error(
        val error: AppError,
    ) : DevicesUiState
}
