package com.calypsan.listenup.client.presentation.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.NotificationPreferenceDto
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.NotificationRepository
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Screen state for the per-type notification preference toggles. */
sealed interface NotificationPrefsUiState {
    /** Initial state while the first server load is in flight. */
    data object Loading : NotificationPrefsUiState

    /** One row per registered type, in registry order. */
    data class Data(
        val prefs: List<NotificationPreferenceDto>,
    ) : NotificationPrefsUiState

    /** Load failed — carries the typed error; the screen renders it via AppError.localized(). */
    data class Error(
        val error: AppError,
    ) : NotificationPrefsUiState
}

/**
 * Backs the Settings notification sub-screen: loads the server-resolved per-type preferences and
 * applies toggles optimistically, reverting (and surfacing on the ErrorBus) when the server refuses.
 */
class NotificationPrefsViewModel(
    private val repo: NotificationRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    /** Current preferences state — preferences are online RPC, so this is load-then-toggle. */
    val uiState: StateFlow<NotificationPrefsUiState>
        field = MutableStateFlow<NotificationPrefsUiState>(NotificationPrefsUiState.Loading)

    init {
        refresh()
    }

    /** (Re)loads the resolved preferences from the server. */
    fun refresh() {
        viewModelScope.launch {
            when (val result = repo.getPreferences()) {
                is AppResult.Success -> {
                    uiState.value = NotificationPrefsUiState.Data(result.data)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    uiState.value = NotificationPrefsUiState.Error(result.error)
                }
            }
        }
    }

    /** Optimistically applies the toggle; reverts and surfaces the error if the server refuses. */
    fun setPreference(
        type: String,
        preference: NotificationPreference,
    ) {
        val before = uiState.value as? NotificationPrefsUiState.Data ?: return
        uiState.value =
            NotificationPrefsUiState.Data(
                before.prefs.map { if (it.type == type) it.copy(preference = preference) else it },
            )
        viewModelScope.launch {
            when (val result = repo.updatePreference(type, preference)) {
                is AppResult.Success -> {
                    Unit
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    uiState.value = before
                }
            }
        }
    }
}
