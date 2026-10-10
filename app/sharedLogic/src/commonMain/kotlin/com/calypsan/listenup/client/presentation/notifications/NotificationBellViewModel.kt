package com.calypsan.listenup.client.presentation.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.repository.NotificationRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Unread count for the shell bell — always-on, cheap, reads the same table as the list. */
class NotificationBellViewModel(
    repo: NotificationRepository,
) : ViewModel() {
    /** Live unread count; the badge hides at zero. */
    val unreadCount: StateFlow<Int> =
        repo.observeUnreadCount().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )
}
