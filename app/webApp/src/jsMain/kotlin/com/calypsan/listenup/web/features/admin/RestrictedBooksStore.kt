package com.calypsan.listenup.web.features.admin

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.visibility.RestrictedBooksViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * The admin-gated restricted-book set behind the lock on every web book card, and its teardown.
 * Open for as long as the shell is, like the inbox badge: every page's cards read it.
 */
class RestrictedBooksSession(
    val restrictedBookIds: StateFlow<Set<String>>,
    val close: () -> Unit,
)

/** Opens the shell's one [RestrictedBooksSession]. Production resolves the ViewModel; specs hand over ids. */
typealias OpenRestrictedBooks = () -> RestrictedBooksSession

/** The real session: [RestrictedBooksViewModel] in its own store, cleared on close. */
fun graphRestrictedBooks(koin: Koin): OpenRestrictedBooks =
    {
        val viewModel = koin.get<RestrictedBooksViewModel>()
        val store = ViewModelStore().apply { put("restricted-books", viewModel) }
        RestrictedBooksSession(restrictedBookIds = viewModel.restrictedBookIds, close = store::clear)
    }

/** A set that never changes — the shape specs use in place of the graph. */
fun fixedRestrictedBooks(ids: Set<String> = emptySet()): OpenRestrictedBooks =
    { RestrictedBooksSession(MutableStateFlow(ids), close = {}) }
