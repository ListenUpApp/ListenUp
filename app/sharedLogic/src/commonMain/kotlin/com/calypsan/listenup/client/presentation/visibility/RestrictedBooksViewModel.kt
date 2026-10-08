package com.calypsan.listenup.client.presentation.visibility

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.repository.BookVisibilityRepository
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The lock on every book card. One shell-level feed of the books an admin should see marked as
 * restricted, which each platform publishes once at its root — beside the inbox's
 * `InboxBadgeViewModel` — and every card primitive consults by the book id it already carries.
 *
 * Deliberately not folded into each list's own state: book cards are drawn from nine different
 * projections across a dozen screens, several loaded once rather than observed, and the marker
 * depends on nothing but the book's id. Always empty on a member's device — the repository gates
 * it — so no card needs a role check of its own. Never contains a held book, so the lock never
 * shares a cover with the inbox's *Held* marker.
 */
class RestrictedBooksViewModel(
    bookVisibilityRepository: BookVisibilityRepository,
) : ViewModel() {
    /** Ids of the restricted books, as the plain strings every card already holds. */
    val restrictedBookIds: StateFlow<Set<String>> =
        bookVisibilityRepository
            .observeRestrictedBookIds()
            .map { ids -> ids.mapTo(mutableSetOf()) { it.value } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptySet(),
            )

    /**
     * Cancels this ViewModel's coroutines. Idempotent. Android and web clear it through their
     * `ViewModelStore`; iOS has none, so its observer calls this from its `isolated deinit` (#1192).
     */
    fun close() {
        viewModelScope.cancel()
    }
}
