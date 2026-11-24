package com.calypsan.listenup.client.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi

/**
 * ViewModel for the Devices screen.
 *
 * Lists the caller's active sessions ("devices"), resolving each into a
 * display-ready [DeviceRow]. Supports revoking a single device and signing
 * out every other device. State is produced via `stateIn(WhileSubscribed)` driven by
 * a [refresh] trigger — revoking a device bumps the trigger to re-fetch the
 * authoritative session list rather than mutating the list optimistically.
 *
 * @property authRepository Port for the auth contract (session listing + revocation).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModel(
    private val authRepository: AuthRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private val signingOut = MutableStateFlow<Set<String>>(emptySet())
    private val signingOutOthers = MutableStateFlow(false)

    val uiState: StateFlow<DevicesUiState> =
        combine(refresh.mapLatest { load() }, signingOut, signingOutOthers) { loaded, ids, others ->
            if (loaded is DevicesUiState.Ready) loaded.copy(signingOut = ids, signingOutOthers = others) else loaded
        }.stateIn(
            scope = viewModelScope,
            started = WhileSubscribedForUi,
            initialValue = DevicesUiState.Loading,
        )

    private suspend fun load(): DevicesUiState =
        when (val result = authRepository.listSessions()) {
            is AppResult.Success -> {
                DevicesUiState.Ready(devices = result.data.map { it.toRow() })
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
     * row drops out of [DevicesUiState.Ready] from the authoritative server state;
     * a failure is reported on the [ErrorBus] and the row stays.
     */
    fun revokeDevice(sessionId: String) {
        viewModelScope.launch {
            signingOut.update { it + sessionId }
            val result = authRepository.revokeSession(SessionId(sessionId))
            signingOut.update { it - sessionId }
            settle(result)
        }
    }

    /**
     * Sign out every device except this one. The server spares the session the request is made
     * from, so this device stays signed in; on success the list is re-fetched and only this
     * device's row remains. A second call while one is in flight is ignored; a failure is reported
     * on the [ErrorBus].
     */
    fun signOutOtherDevices() {
        if (!signingOutOthers.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            val result = authRepository.revokeOtherSessions()
            signingOutOthers.value = false
            settle(result)
        }
    }

    private fun settle(result: AppResult<Unit>) {
        when (result) {
            is AppResult.Success -> refresh.update { it + 1 }
            is AppResult.Failure -> errorBus.emit(result.error)
        }
    }

    private fun SessionSummary.toRow(): DeviceRow =
        DeviceRow(
            sessionId = id.value,
            displayName =
                resolveName(
                    label = label,
                    deviceName = deviceInfo?.deviceName,
                    deviceModel = deviceInfo?.deviceModel,
                    userAgent = userAgent,
                ),
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
