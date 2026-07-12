package com.calypsan.listenup.client.presentation.campfire

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.repository.BookRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * ViewModel backing the Discover "Start a campfire" book picker sheet.
 *
 * A searchable list of the caller's own library, offline-first like every other book list on the
 * client: a blank [query] shows every book ([BookRepository.observeBookListItems]); a non-blank
 * query re-queries via [BookRepository.search] (server FTS5 when online, local FTS5 fallback
 * otherwise — see that method's KDoc). There is no dedicated create surface on Discover itself, so
 * picking a book here hands its id back to the caller, which routes to that book's detail screen
 * with the Campfire create flow pre-armed (see `BookDetail` route's `openCampfireCreate`).
 */
class CampfireBookPickerViewModel(
    private val bookRepository: BookRepository,
) : ViewModel() {
    /** Current search text; blank shows the full library. */
    val query: StateFlow<String>
        field = MutableStateFlow("")

    /** Updates [query], driving a fresh [books] emission. */
    fun onQueryChange(text: String) {
        query.value = text
    }

    /** The books matching [query], reactively. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val books: StateFlow<List<BookListItem>> =
        query
            .flatMapLatest { text ->
                if (text.isBlank()) {
                    bookRepository.observeBookListItems()
                } else {
                    bookRepository.search(text)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = emptyList(),
            )
}
