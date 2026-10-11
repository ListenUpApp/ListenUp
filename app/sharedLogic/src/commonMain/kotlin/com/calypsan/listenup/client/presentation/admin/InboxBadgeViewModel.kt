package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi

/**
 * How many books are waiting in the admin inbox — the count behind the Library's inbox entry and the
 * badge on the Library destination in navigation. Both hide at zero.
 *
 * Reads the same Room held set as the inbox page and the library's exclusion
 * ([InboxRepository.observeHeldBookIds]), so the badge can never disagree with what the inbox lists.
 * It counts held books only; scan issues stay on the inbox page. Zero for anyone who is not an admin:
 * a member's held set is already empty, and the [UserRepository.observeIsAdmin] gate makes that a
 * stated rule rather than a coincidence of sync.
 */
class InboxBadgeViewModel(
    userRepository: UserRepository,
    inboxRepository: InboxRepository,
) : ViewModel() {
    /**
     * The held set as this viewer sees it, oldest hold first: everything held for an admin, nothing
     * for anyone else. One subscription to Room and to the admin flag, shared by [heldCount] and
     * [previewBookIds] rather than each opening its own.
     */
    private val visibleHeld: Flow<List<BookId>> =
        combine(userRepository.observeIsAdmin(), inboxRepository.observeHeldBookIds()) { isAdmin, held ->
            if (isAdmin) held.toList() else emptyList()
        }.shareIn(
            scope = viewModelScope,
            started = WhileSubscribedForUi,
            replay = 1,
        )

    /** Books held for review, or 0 when there are none or the viewer is not an admin. */
    val heldCount: StateFlow<Int> =
        visibleHeld
            .map { it.size }
            .stateIn(
                scope = viewModelScope,
                started = WhileSubscribedForUi,
                initialValue = 0,
            )

    /**
     * The newest held books, newest first, at most [PREVIEW_SIZE] — the covers the Library entry
     * fans beside its count. Ids rather than list items because every client resolves a cover from
     * the id alone. Empty whenever [heldCount] is 0, including for anyone who is not an admin.
     */
    val previewBookIds: StateFlow<List<String>> =
        visibleHeld
            .map { held -> held.takeLast(PREVIEW_SIZE).reversed().map { it.value } }
            .stateIn(
                scope = viewModelScope,
                started = WhileSubscribedForUi,
                initialValue = emptyList(),
            )
}

/** How many held covers the Library entry fans out (canvas: three). */
private const val PREVIEW_SIZE = 3
