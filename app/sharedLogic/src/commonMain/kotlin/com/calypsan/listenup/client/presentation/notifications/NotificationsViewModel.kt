package com.calypsan.listenup.client.presentation.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AppNotification
import com.calypsan.listenup.client.domain.repository.NotificationRepository
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Screen state for the notification inbox list. */
sealed interface NotificationsUiState {
    /** First composition, before the Room flow emits. */
    data object Loading : NotificationsUiState

    /** No live notifications. */
    data object Empty : NotificationsUiState

    /** The inbox, newest first. */
    data class Data(
        val notifications: List<AppNotification>,
    ) : NotificationsUiState
}

/**
 * Backs the notification inbox screen: projects [NotificationRepository.observeNotifications] into
 * a sealed [NotificationsUiState], and delegates mark-read taps back to the repository.
 */
class NotificationsViewModel(
    private val repo: NotificationRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    /** Current inbox state derived from the Room-backed notifications observation. */
    val uiState: StateFlow<NotificationsUiState> =
        repo
            .observeNotifications()
            .map { items ->
                if (items.isEmpty()) NotificationsUiState.Empty else NotificationsUiState.Data(items)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = NotificationsUiState.Loading,
            )

    /**
     * Marks [notificationId] read. Failures surface on the global ErrorBus; the row's visual
     * state always derives from Room, so there is nothing to roll back locally.
     */
    fun markRead(notificationId: String) {
        viewModelScope.launch {
            when (val result = repo.markRead(notificationId)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> errorBus.emit(result.error)
            }
        }
    }
}
