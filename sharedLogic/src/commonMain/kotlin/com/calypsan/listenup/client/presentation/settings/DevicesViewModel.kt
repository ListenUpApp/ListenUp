package com.calypsan.listenup.client.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AuthRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * ViewModel for the Devices screen.
 *
 * Lists the caller's active sessions ("devices"), resolving each into a
 * display-ready [DeviceRow]. Supports revoking a single device and signing
 * out everywhere. State is produced via `stateIn(WhileSubscribed)` driven by
 * a [refresh] trigger — revoking a device bumps the trigger to re-fetch the
 * authoritative session list rather than mutating the list optimistically.
 *
 * @property authRepository Port for the auth contract (session listing + revocation).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private val signingOut = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<DevicesUiState> =
        refresh
            .mapLatest { load() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = DevicesUiState.Loading,
            )

    private suspend fun load(): DevicesUiState =
        when (val result = authRepository.listSessions()) {
            is AppResult.Success -> {
                DevicesUiState.Ready(
                    devices = result.data.map { it.toRow() },
                    signingOut = signingOut.value,
                )
            }

            is AppResult.Failure -> {
                DevicesUiState.Error(result.error)
            }
        }

    /** Re-fetch the session list (e.g. after a transient load failure). */
    fun retry() {
        refresh.update { it + 1 }
    }

    /**
     * Revoke a single session. On success, re-fetches the session list so the
     * row drops out of [DevicesUiState.Ready] from the authoritative server state.
     */
    fun revokeDevice(sessionId: String) {
        viewModelScope.launch {
            signingOut.update { it + sessionId }
            val result = authRepository.revokeSession(SessionId(sessionId))
            signingOut.update { it - sessionId }
            if (result is AppResult.Success) refresh.update { it + 1 }
        }
    }

    /** Revoke every session for the caller, then invoke [onDone] (e.g. navigate to login). */
    fun signOutEverywhere(onDone: () -> Unit) {
        viewModelScope.launch {
            val _ = authRepository.logoutAll()
            // Never stranded: run the nav teardown unconditionally even if the
            // server-side revoke failed — it clears local tokens and routes to
            // login regardless, and the server revokes on next refresh as backstop.
            onDone()
        }
    }

    private fun SessionSummary.toRow(): DeviceRow =
        DeviceRow(
            sessionId = id.value,
            displayName = resolveName(label, deviceInfo?.deviceName, deviceInfo?.deviceModel, userAgent),
            secondary = secondaryOf(deviceInfo),
            lastUsedAt = lastUsedAt,
            isCurrent = current,
            deviceType = deviceInfo?.deviceType,
        )

    companion object {
        /**
         * Resolve a device's display name by precedence:
         * label > deviceName > deviceModel > userAgent > "Unknown device".
         * Blank candidates are skipped.
         */
        fun resolveName(
            label: String?,
            deviceName: String?,
            deviceModel: String?,
            userAgent: String?,
        ): String =
            sequenceOf(label, deviceName, deviceModel, userAgent)
                .firstOrNull { !it.isNullOrBlank() } ?: "Unknown device"

        /**
         * Build the secondary descriptor — "platform platformVersion · clientName clientVersion".
         * Blank-safe: omits empty segments and returns "" when no metadata is present.
         */
        fun secondaryOf(info: DeviceInfo?): String {
            if (info == null) return ""
            val platform =
                listOfNotNull(
                    info.platform,
                    info.platformVersion,
                ).filter { it.isNotBlank() }.joinToString(" ")
            val client = listOfNotNull(info.clientName, info.clientVersion).filter { it.isNotBlank() }.joinToString(" ")
            return listOf(platform, client).filter { it.isNotBlank() }.joinToString(" · ")
        }
    }
}
