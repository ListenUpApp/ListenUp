package com.calypsan.listenup.client.presentation.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AppNotification
import com.calypsan.listenup.client.domain.repository.NotificationRepository
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi

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
 *
 * @property appScope App-lifetime scope the mark-read writes run on, so they outlive the screen.
 */
class NotificationsViewModel(
    private val repo: NotificationRepository,
    private val errorBus: ErrorBus,
    private val appScope: CoroutineScope,
) : ViewModel() {
    /** Current inbox state derived from the Room-backed notifications observation. */
    val uiState: StateFlow<NotificationsUiState> =
        repo
            .observeNotifications()
            .map { items ->
                if (items.isEmpty()) NotificationsUiState.Empty else NotificationsUiState.Data(items)
            }.stateIn(
                scope = viewModelScope,
                started = WhileSubscribedForUi,
                initialValue = NotificationsUiState.Loading,
            )

    /**
     * Marks [notificationId] read. Failures surface on the global ErrorBus; the row's visual
     * state always derives from Room, so there is nothing to roll back locally.
     *
     * Runs on [appScope], not viewModelScope: the tap that marks a row read usually opens what it
     * points at in the same gesture, and a client that ends the inbox's scope as it leaves (web
     * does) would otherwise cancel the write before it lands.
     */
    fun markRead(notificationId: String) {
        appScope.launch {
            when (val result = repo.markRead(notificationId)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> errorBus.emit(result.error)
            }
        }
    }
}
